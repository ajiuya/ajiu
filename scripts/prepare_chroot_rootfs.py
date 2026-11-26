"""Encode the bundled Alpine archive as an AJIF v1 stream; never extract or execute it."""

import argparse
import ctypes
from dataclasses import dataclass, fields
import errno
import hashlib
import json
import os
from pathlib import Path
import stat
import struct
import sys
import tarfile
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/_armAssets/container/arm/alpine-rootfs.bin"
OUTPUT = ROOT / "app/build/reports/chroot-runtime/rootfs-install.stream"
STREAM_HEADER = b"AJIF" + struct.pack(">HH", 1, 0)
RECORD_HEADER = struct.Struct(">BBHIIIQ")
END_RECORD = RECORD_HEADER.pack(255, 0, 0, 0, 0, 0, 0)
EXPECTED_SOURCE_SHA = "f25a96d2846a4bc439093107c1b48a8b0c93dcb411e2cb9cfded6f790b2bc001"
EXPECTED_SOURCE_BYTES = 3831912
EXPECTED_ARCHIVE_COUNTS = {"dir": 98, "file": 88, "symlink": 335, "hardlink": 0}
EXPECTED_OWNERS = {"0:0": 520, "0:42": 1}
KINDS = {tarfile.DIRTYPE: (1, "dir"), tarfile.REGTYPE: (2, "file"),
         tarfile.AREGTYPE: (2, "file"), tarfile.SYMTYPE: (3, "symlink"),
         tarfile.LNKTYPE: (4, "hardlink")}
METADATA_TYPES = (tarfile.XHDTYPE, tarfile.XGLTYPE, tarfile.GNUTYPE_LONGNAME,
                  tarfile.GNUTYPE_LONGLINK)
CHUNK_BYTES = 64 * 1024
MAX_METADATA_DEPTH = 16


class EncodingError(ValueError):
    pass


@dataclass(frozen=True)
class Limits:
    entries: int = 65536
    file_bytes: int = 128 * 1024 * 1024
    total_file_bytes: int = 512 * 1024 * 1024
    path_bytes: int = 4096
    link_bytes: int = 4096
    names_bytes: int = 16 * 1024 * 1024
    archive_headers: int = 65536
    metadata_bytes: int = 16 * 1024 * 1024


class _BoundedTarFile(tarfile.TarFile):
    def __init__(self, *args, limits, **kwargs):
        self._encoder_limits = limits
        self._header_count = 0
        self._metadata_bytes = 0
        self._metadata_depth = 0
        super().__init__(*args, **kwargs)


def _check_raw_header(block):
    if len(block) != tarfile.BLOCKSIZE:
        return
    for start, end in ((0, 100), (157, 257), (345, 500)):
        value = block[start:end]
        first_nul = value.find(b"\0")
        if first_nul >= 0 and any(value[first_nul:]):
            raise EncodingError("Embedded NUL in tar path or link field")


class _StrictTarInfo(tarfile.TarInfo):
    @classmethod
    def frombuf(cls, block, encoding, errors):
        _check_raw_header(block)
        return super().frombuf(block, encoding, errors)

    @classmethod
    def _frombuf(cls, block, encoding, errors, **kwargs):
        _check_raw_header(block)
        return super()._frombuf(block, encoding, errors, **kwargs)

    def _proc_member(self, archive):
        archive._header_count += 1
        if archive._header_count > archive._encoder_limits.archive_headers:
            raise EncodingError("Raw archive header count limit exceeded")
        if self.type not in KINDS and self.type not in METADATA_TYPES:
            raise EncodingError("Unsupported tar entry type")
        if self.size < 0:
            raise EncodingError("Negative tar data length")
        if self.type in METADATA_TYPES:
            if self.size > CHUNK_BYTES:
                raise EncodingError("Tar metadata exceeds its bounded buffer")
            size = self._block(self.size)
            if archive._metadata_bytes + size > archive._encoder_limits.metadata_bytes:
                raise EncodingError("Total tar metadata bytes limit exceeded")
            if archive._metadata_depth >= MAX_METADATA_DEPTH:
                raise EncodingError("Tar metadata nesting limit exceeded")
            archive._metadata_bytes += size
            archive._metadata_depth += 1
            try:
                return super()._proc_member(archive)
            finally:
                archive._metadata_depth -= 1
        if self.type not in (tarfile.REGTYPE, tarfile.AREGTYPE) and self.size != 0:
            raise EncodingError("Non-file tar entry has data")
        return super()._proc_member(archive)

    def _proc_sparse(self, archive):
        raise EncodingError("Sparse tar entries are unsupported")

    def _proc_gnusparse_00(self, following, raw_headers):
        raise EncodingError("Sparse tar entries are unsupported")

    def _proc_gnusparse_01(self, following, pax_headers):
        raise EncodingError("Sparse tar entries are unsupported")

    def _proc_gnusparse_10(self, following, pax_headers, archive):
        raise EncodingError("Sparse tar entries are unsupported")

    def _proc_gnulong(self, archive):
        block_size = self._block(self.size)
        payload = archive.fileobj.read(block_size)
        if len(payload) != block_size or not self.size or self.size > 4097:
            raise EncodingError("Invalid GNU long-name data length")
        value = payload[:self.size]
        if value[-1:] != b"\0" or b"\0" in value[:-1]:
            raise EncodingError("Invalid GNU long-name terminator")
        text = value[:-1].decode("utf-8", "strict")
        following = self.fromtarfile(archive)
        following.offset = self.offset
        if self.type == tarfile.GNUTYPE_LONGNAME:
            following.name = text.rstrip("/") if following.isdir() else text
        else:
            following.linkname = text
        return following


class _DigestReader:
    def __init__(self, source):
        self.source = source
        self.digest = hashlib.sha256()
        self.bytes = 0

    def read(self, size=-1):
        data = self.source.read(size)
        self.digest.update(data)
        self.bytes += len(data)
        return data


class _DigestWriter:
    def __init__(self, destination):
        self.destination = destination
        self.digest = hashlib.sha256()
        self.bytes = 0

    def write(self, data):
        position = 0
        while position < len(data):
            written = self.destination.write(data[position:])
            if not isinstance(written, int) or not 0 < written <= len(data) - position:
                raise EncodingError("Incomplete stream write")
            self.digest.update(data[position:position + written])
            self.bytes += written
            position += written


def _text_bytes(value, limit, label):
    if not value or "\0" in value:
        raise EncodingError(f"Empty or NUL-containing {label}")
    data = value.encode("utf-8", "strict")
    if not 1 <= len(data) <= limit:
        raise EncodingError(f"Invalid {label} byte length")
    return data


def _relative_path(value, limit, *, normalize):
    if "\0" in value:
        raise EncodingError("NUL-containing path")
    if normalize:
        while value.startswith("./"):
            value = value[2:]
    data = _text_bytes(value, limit, "path")
    components = data.split(b"/")
    if any(part in (b"", b".", b"..") for part in components):
        raise EncodingError("Path is not a strict relative path")
    if any(len(part) > 255 for part in components):
        raise EncodingError("Path component exceeds 255 UTF-8 bytes")
    return value, data


def _validate_metadata(member, kind):
    for name in ("uid", "gid"):
        number = getattr(member, name)
        if not isinstance(number, int) or not 0 <= number <= 65535:
            raise EncodingError("Owner identifier out of range")
    mask = 0o1777 if kind == 1 else 0o777
    if not isinstance(member.mode, int) or member.mode < 0 or member.mode & ~mask:
        raise EncodingError("Forbidden mode bits")
    if not isinstance(member.size, int) or member.size < 0:
        raise EncodingError("Invalid tar data length")
    if kind != 2 and member.size != 0:
        raise EncodingError("Non-file tar entry has data")
    if member.sparse is not None or any(key.startswith("GNU.sparse.") for key in member.pax_headers):
        raise EncodingError("Sparse tar entries are unsupported")
    for key in ("uid", "gid", "size"):
        if key in member.pax_headers:
            value = member.pax_headers[key]
            if not value or not value.isascii() or not value.isdecimal():
                raise EncodingError("Invalid numeric tar metadata")
            if int(value) != getattr(member, key):
                raise EncodingError("Inconsistent numeric tar metadata")


def encode_archive(input_filelike, output_filelike, *, limits=Limits()):
    """Read an archive and write AJIF; leave both caller-owned streams open.

    A failed call may leave a partial output, without an end record. Limits may
    only tighten the frozen protocol bounds, allowing small offline test cases.
    """
    for field in fields(Limits):
        value = getattr(limits, field.name)
        if not isinstance(value, int) or not 0 <= value <= getattr(Limits(), field.name):
            raise EncodingError("Limits must not exceed the frozen protocol bounds")
    source = _DigestReader(input_filelike)
    output = _DigestWriter(output_filelike)
    counts = dict.fromkeys(("dir", "file", "symlink", "hardlink"), 0)
    archive_counts = counts.copy()
    owners = {}
    seen = set()
    regular_files = set()
    file_bytes = link_bytes = path_bytes = skipped_roots = sticky_dirs = 0
    output.write(STREAM_HEADER)
    try:
        with _BoundedTarFile.open(fileobj=source, mode="r|*", encoding="utf-8", errors="strict",
                                  tarinfo=_StrictTarInfo, stream=True, limits=limits) as archive:
            for member in archive:
                if member.type not in KINDS:
                    raise EncodingError("Unsupported tar entry type")
                kind, label = KINDS[member.type]
                _validate_metadata(member, kind)
                archive_counts[label] += 1
                owner = f"{member.uid}:{member.gid}"
                owners[owner] = owners.get(owner, 0) + 1
                raw_path = member.name
                while raw_path.startswith("./"):
                    raw_path = raw_path[2:]
                if raw_path == "." and kind == 1:
                    if skipped_roots:
                        raise EncodingError("Duplicate normalized root directory")
                    skipped_roots += 1
                    continue
                path, encoded_path = _relative_path(member.name, limits.path_bytes, normalize=True)
                if path in seen:
                    raise EncodingError("Duplicate normalized archive path")
                if len(seen) >= limits.entries:
                    raise EncodingError("Entry count limit exceeded")
                if path_bytes + len(seen) + len(encoded_path) + 1 > limits.names_bytes:
                    raise EncodingError("Total path name bytes limit exceeded")
                if kind == 2:
                    if member.size > limits.file_bytes:
                        raise EncodingError("Per-file data limit exceeded")
                    if file_bytes + member.size > limits.total_file_bytes:
                        raise EncodingError("Total file data limit exceeded")
                    data_length = member.size
                    data = None
                elif kind in (3, 4):
                    data = _text_bytes(member.linkname, limits.link_bytes, "link target")
                    if kind == 4:
                        target, _ = _relative_path(member.linkname, limits.path_bytes, normalize=False)
                        if target not in regular_files:
                            raise EncodingError("Hardlink target is not a preceding regular file")
                    data_length = len(data)
                else:
                    data = b""
                    data_length = 0
                output.write(RECORD_HEADER.pack(kind, 0, len(encoded_path), member.mode,
                                                member.uid, member.gid, data_length))
                output.write(encoded_path)
                if kind == 2:
                    remaining = data_length
                    with archive.extractfile(member) as content:
                        while remaining:
                            chunk = content.read(min(remaining, CHUNK_BYTES))
                            if not chunk or len(chunk) > remaining:
                                raise EncodingError("Truncated file data")
                            output.write(chunk)
                            remaining -= len(chunk)
                    regular_files.add(path)
                    file_bytes += data_length
                else:
                    output.write(data)
                    link_bytes += data_length
                seen.add(path)
                counts[label] += 1
                path_bytes += len(encoded_path)
                sticky_dirs += int(kind == 1 and bool(member.mode & 0o1000))
        while source.read(CHUNK_BYTES):
            pass
    except EncodingError:
        raise
    except (tarfile.TarError, UnicodeError, EOFError, OSError, ValueError) as error:
        raise EncodingError("Archive decoding failed") from error
    output.write(END_RECORD)
    return {
        "sourceSHA": source.digest.hexdigest(), "sourceBytes": source.bytes,
        "streamSHA": output.digest.hexdigest(), "streamBytes": output.bytes,
        "entryCount": len(seen), "counts": counts, "archiveCounts": archive_counts,
        "archiveEntryCount": sum(archive_counts.values()), "ownerCounts": owners,
        "archiveHeaderCount": archive._header_count, "metadataBytes": archive._metadata_bytes,
        "fileDataBytes": file_bytes, "linkDataBytes": link_bytes, "pathBytes": path_bytes,
        "namesBytes": path_bytes + len(seen),
        "skippedRootDirectories": skipped_roots, "stickyDirectories": sticky_dirs,
    }


def _open_regular(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise EncodingError("Expected an ordinary file")
        return os.fdopen(descriptor, "rb")
    except BaseException:
        os.close(descriptor)
        raise


def _inspect_existing(path):
    try:
        source = _open_regular(path)
    except FileNotFoundError:
        return
    with source:
        while source.read(CHUNK_BYTES):
            pass


def _same_contents(first, second):
    with _open_regular(first) as left, _open_regular(second) as right:
        if os.fstat(left.fileno()).st_size != os.fstat(right.fileno()).st_size:
            return False
        while True:
            chunk = left.read(CHUNK_BYTES)
            if chunk != right.read(CHUNK_BYTES):
                return False
            if not chunk:
                return True


def _rename_noreplace(temporary, destination):
    try:
        rename = ctypes.CDLL(None, use_errno=True).renameat2
    except AttributeError:
        raise EncodingError("Atomic renameat2 is unavailable; no output was replaced") from None
    rename.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint)
    rename.restype = ctypes.c_int
    result = rename(-100, os.fsencode(temporary), -100, os.fsencode(destination), 1)
    if result != 0:
        code = ctypes.get_errno()
        if code in (errno.ENOSYS, errno.EOPNOTSUPP):
            raise EncodingError("Atomic no-replace rename is unsupported; no fallback is allowed")
        raise OSError(code, os.strerror(code))


def _commit_stream(temporary, destination):
    try:
        _rename_noreplace(temporary, destination)
    except FileExistsError:
        if not _same_contents(temporary, destination):
            raise EncodingError("Existing output differs; it was not replaced") from None


def _verify_fixed_source(source):
    if os.fstat(source.fileno()).st_size != EXPECTED_SOURCE_BYTES:
        raise EncodingError("Fixed asset byte length mismatch")
    reader = _DigestReader(source)
    while reader.read(CHUNK_BYTES):
        if reader.bytes > EXPECTED_SOURCE_BYTES:
            raise EncodingError("Fixed asset byte length changed during verification")
    if reader.bytes != EXPECTED_SOURCE_BYTES or reader.digest.hexdigest() != EXPECTED_SOURCE_SHA:
        raise EncodingError("Fixed asset SHA or byte length mismatch")
    source.seek(0)


def prepare_rootfs():
    _inspect_existing(OUTPUT)
    with _open_regular(SOURCE) as source:
        _verify_fixed_source(source)
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        descriptor, name = tempfile.mkstemp(prefix=".rootfs-install.", suffix=".tmp", dir=OUTPUT.parent)
        temporary = Path(name)
        try:
            with os.fdopen(descriptor, "wb") as destination:
                result = encode_archive(source, destination)
                if (result["sourceSHA"] != EXPECTED_SOURCE_SHA
                        or result["sourceBytes"] != EXPECTED_SOURCE_BYTES
                        or result["archiveCounts"] != EXPECTED_ARCHIVE_COUNTS
                        or result["ownerCounts"] != EXPECTED_OWNERS):
                    raise EncodingError("Fixed asset metadata mismatch: " + json.dumps({
                        key: result[key] for key in ("sourceSHA", "sourceBytes", "archiveCounts")
                    }, sort_keys=True))
                destination.flush()
                os.fsync(destination.fileno())
            _commit_stream(temporary, OUTPUT)
            return result
        finally:
            temporary.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=(
        "Offline AJIF v1 encoder for the fixed bundled Alpine asset only. "
        "Reads app/src/_armAssets/container/arm/alpine-rootfs.bin and atomically publishes "
        "app/build/reports/chroot-runtime/rootfs-install.stream without replacing different data. "
        "Does not extract, install, execute, or accept input/output paths."))
    parser.parse_args(argv)
    try:
        result = prepare_rootfs()
    except EncodingError as error:
        print(json.dumps({"error": str(error)}, sort_keys=True), file=sys.stderr)
        return 1
    except OSError:
        print(json.dumps({"error": "Fixed stream preparation could not access ordinary files"}),
              file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
