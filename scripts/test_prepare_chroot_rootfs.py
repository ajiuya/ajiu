import contextlib
from dataclasses import replace
import hashlib
import io
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest
from unittest import mock

import prepare_chroot_rootfs as encoder


def member(name="file", *, kind=tarfile.REGTYPE, data=b"", target="", mode=0o755,
           uid=0, gid=0, size=None, pax=None):
    info = tarfile.TarInfo(name)
    info.type = kind
    info.mode = mode
    info.uid = uid
    info.gid = gid
    info.linkname = target
    info.size = len(data) if size is None else size
    info.pax_headers = pax or {}
    return info, data


def archive_bytes(*entries, archive_format=tarfile.PAX_FORMAT):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w", format=archive_format,
                      encoding="utf-8", errors="surrogateescape") as archive:
        for info, data in entries:
            archive.addfile(info, io.BytesIO(data) if data else None)
    return output.getvalue()


def raw_archive(entry, *, mode=None, raw_path=None):
    info, data = entry
    header = bytearray(info.tobuf(format=tarfile.GNU_FORMAT, encoding="utf-8",
                                  errors="surrogateescape"))
    if mode is not None:
        header[100:108] = tarfile.itn(mode, 8, tarfile.GNU_FORMAT)
    if raw_path is not None:
        header[:100] = raw_path.ljust(100, b"\0")
    header[148:156] = b"        "
    header[148:156] = f"{sum(header):06o}\0 ".encode("ascii")
    return bytes(header) + data + bytes(-len(data) % 512) + bytes(1024)


class EncodingTest(unittest.TestCase):
    def encode(self, data, *, limits=encoder.Limits()):
        output = io.BytesIO()
        result = encoder.encode_archive(io.BytesIO(data), output, limits=limits)
        stream = output.getvalue()
        self.assertEqual(hashlib.sha256(data).hexdigest(), result["sourceSHA"])
        self.assertEqual(len(data), result["sourceBytes"])
        self.assertEqual(hashlib.sha256(stream).hexdigest(), result["streamSHA"])
        self.assertEqual(len(stream), result["streamBytes"])
        return stream, result

    def decode(self, stream):
        self.assertEqual(b"AJIF\x00\x01\x00\x00", stream[:8])
        header = struct.Struct(">BBHIIIQ")
        self.assertEqual(24, header.size)
        records = []
        offset = 8
        while True:
            self.assertGreaterEqual(len(stream) - offset, header.size)
            kind, reserved, path_length, mode, uid, gid, data_length = header.unpack_from(stream, offset)
            offset += header.size
            self.assertEqual(0, reserved)
            if kind == 255:
                self.assertEqual((0, 0, 0, 0, 0), (path_length, mode, uid, gid, data_length))
                self.assertEqual(len(stream), offset)
                return records
            self.assertIn(kind, (1, 2, 3, 4))
            self.assertTrue(1 <= path_length <= 4096)
            self.assertGreaterEqual(len(stream) - offset, path_length + data_length)
            path = stream[offset:offset + path_length]
            offset += path_length
            data = stream[offset:offset + data_length]
            offset += data_length
            self.assertNotIn(b"\0", path)
            records.append((kind, path.decode("utf-8", "strict"), mode, uid, gid, data))

    def reject(self, data, *, limits=encoder.Limits()):
        output = io.BytesIO()
        with self.assertRaises(encoder.EncodingError):
            encoder.encode_archive(io.BytesIO(data), output, limits=limits)

    def test_empty_archive_has_exact_header_and_terminal_record(self):
        stream, result = self.encode(archive_bytes())
        self.assertEqual(b"AJIF\x00\x01\x00\x00" + b"\xff" + bytes(23), stream)
        self.assertEqual([], self.decode(stream))
        self.assertEqual(0, result["entryCount"])

    def test_exact_wire_record_and_final_end(self):
        stream, _ = self.encode(archive_bytes(member("x", data=b"\x00\xff", mode=0o640, uid=65535, gid=42)))
        expected = (b"AJIF\x00\x01\x00\x00"
                    + struct.pack(">BBHIIIQ", 2, 0, 1, 0o640, 65535, 42, 2)
                    + b"x\x00\xff" + b"\xff" + bytes(23))
        self.assertEqual(expected, stream)
        self.assertEqual([(2, "x", 0o640, 65535, 42, b"\x00\xff")], self.decode(stream))

    def test_allowed_types_targets_and_directory_sticky_are_preserved(self):
        data = archive_bytes(member(".", kind=tarfile.DIRTYPE),
                             member("././dir/", kind=tarfile.DIRTYPE, mode=0o1777),
                             member("./dir/file", data=b"payload", uid=1, gid=42),
                             member("absolute", kind=tarfile.SYMTYPE, target="/guest/../target"),
                             member("relative", kind=tarfile.SYMTYPE, target="../../outside"),
                             member("hard", kind=tarfile.LNKTYPE, target="dir/file"))
        stream, result = self.encode(data)
        records = self.decode(stream)
        self.assertEqual([1, 2, 3, 3, 4], [record[0] for record in records])
        self.assertEqual(("dir", 0o1777), records[0][1:3])
        self.assertEqual(b"/guest/../target", records[2][-1])
        self.assertEqual(b"../../outside", records[3][-1])
        self.assertEqual(b"dir/file", records[4][-1])
        self.assertEqual(1, result["skippedRootDirectories"])
        self.assertEqual(1, result["stickyDirectories"])
        self.assertEqual(5, result["entryCount"])
        self.assertEqual({"dir": 1, "file": 1, "symlink": 2, "hardlink": 1}, result["counts"])

    def test_absolute_parent_empty_and_noncanonical_paths_are_rejected(self):
        for path in ("/absolute", "../escape", "a/../b", "./../escape", "", ".", "a/./b", "a//b", "./"):
            with self.subTest(path=repr(path)):
                self.reject(archive_bytes(member(path)))

    def test_normalized_duplicates_are_rejected_across_types(self):
        for first, second in ((member("x"), member("./x")),
                              (member("x", kind=tarfile.DIRTYPE), member("x")),
                              (member("././x"), member("x", kind=tarfile.SYMTYPE, target="y"))):
            with self.subTest(first=first[0].type, second=second[0].type):
                self.reject(archive_bytes(first, second))

    def test_nul_paths_and_targets_cannot_be_truncated(self):
        self.reject(raw_archive(member(), raw_path=b"safe\0hidden"))
        self.reject(archive_bytes(member("fallback", pax={"path": "safe\0hidden"})))
        self.reject(archive_bytes(member("link", kind=tarfile.SYMTYPE,
                                         pax={"linkpath": "safe\0hidden"})))
        self.reject(raw_archive(member("link", kind=tarfile.SYMTYPE, target="safe\0hidden")))
        longname = raw_archive(member("././@LongLink", kind=tarfile.GNUTYPE_LONGNAME,
                                      data=b"safe\0hidden\0"))
        self.reject(longname[:-1024] + raw_archive(member("fallback")))

    def test_special_sparse_and_unknown_entry_types_are_rejected(self):
        for kind in (tarfile.CHRTYPE, tarfile.BLKTYPE, tarfile.FIFOTYPE, tarfile.GNUTYPE_SPARSE,
                     tarfile.CONTTYPE, b"s", b"Z"):
            with self.subTest(kind=kind):
                self.reject(raw_archive(member("special", kind=kind)))
        self.reject(archive_bytes(member("sparse", pax={"GNU.sparse.map": "0,0"})))

    def test_sparse_entry_points_reject_before_payload_reads_or_map_construction(self):
        info = encoder._StrictTarInfo("sparse")
        following = tarfile.TarInfo("file")
        archive = mock.Mock()
        archive.fileobj.read.side_effect = AssertionError("sparse payload must not be read")
        calls = (("_proc_sparse", (archive,)),
                 ("_proc_gnusparse_00", (following, [(1, b"GNU.sparse.offset", b"999999999999999999")])),
                 ("_proc_gnusparse_01", (following, {"GNU.sparse.map": "999999999999999999,0"})),
                 ("_proc_gnusparse_10", (following, {"GNU.sparse.major": "1", "GNU.sparse.minor": "0"}, archive)))
        for name, arguments in calls:
            with self.subTest(entry_point=name):
                with self.assertRaisesRegex(encoder.EncodingError, "Sparse"):
                    getattr(info, name)(*arguments)
                self.assertIsNone(following.sparse)
        archive.fileobj.read.assert_not_called()

    def test_pax_sparse_major_one_huge_fields_is_rejected_before_payload_block_read(self):
        payload = b"999999999999999999999999999999999999\n"
        data = archive_bytes(member("sparse", data=payload,
                                    pax={"GNU.sparse.major": "1", "GNU.sparse.minor": "0"}))
        payload_offset = data.index(payload)
        original_read = tarfile._Stream.read

        def read_before_sparse_map(stream, size):
            if stream.tell() >= payload_offset:
                raise AssertionError("sparse map block must not be read")
            return original_read(stream, size)

        with mock.patch.object(tarfile._Stream, "read", read_before_sparse_map):
            with mock.patch.object(tarfile.TarInfo, "_proc_gnusparse_10",
                                   side_effect=AssertionError("unbounded sparse parser must not run")) as unsafe:
                with self.assertRaisesRegex(encoder.EncodingError, "Sparse"):
                    encoder.encode_archive(io.BytesIO(data), io.BytesIO())
                unsafe.assert_not_called()

    def test_pax_sparse_zero_variants_never_enter_standard_library_map_parsers(self):
        variants = (("_proc_gnusparse_00", {"GNU.sparse.size": "999999999999999999"}),
                    ("_proc_gnusparse_01", {"GNU.sparse.map": "999999999999999999,0"}))
        for name, pax in variants:
            with self.subTest(entry_point=name):
                data = archive_bytes(member("sparse", pax=pax))
                with mock.patch.object(tarfile.TarInfo, name,
                                       side_effect=AssertionError("sparse parser must not run")) as unsafe:
                    with self.assertRaisesRegex(encoder.EncodingError, "Sparse"):
                        encoder.encode_archive(io.BytesIO(data), io.BytesIO())
                    unsafe.assert_not_called()

    def test_raw_header_budget_includes_metadata_and_skipped_root(self):
        data = archive_bytes(member(".", kind=tarfile.DIRTYPE), member("file", pax={"comment": "x"}))
        limits = replace(encoder.Limits(), archive_headers=3, metadata_bytes=512)
        _, result = self.encode(data, limits=limits)
        self.assertEqual(3, result["archiveHeaderCount"])
        self.assertEqual(2, result["archiveEntryCount"])
        self.assertEqual(1, result["entryCount"])
        self.assertEqual(512, result["metadataBytes"])
        self.reject(data, limits=replace(limits, archive_headers=2))
        self.reject(archive_bytes(member(".", kind=tarfile.DIRTYPE)),
                    limits=replace(limits, archive_headers=0))

    def test_total_metadata_budget_accumulates_padded_payload_before_parsing(self):
        data = archive_bytes(member("a", pax={"comment": "first"}),
                             member("b", pax={"comment": "second"}))
        limits = replace(encoder.Limits(), metadata_bytes=1024)
        _, result = self.encode(data, limits=limits)
        self.assertEqual(4, result["archiveHeaderCount"])
        self.assertEqual(1024, result["metadataBytes"])
        self.reject(data, limits=replace(limits, metadata_bytes=1023))
        header_only = raw_archive(member("pax", kind=tarfile.XHDTYPE, size=512))
        with mock.patch.object(tarfile.TarInfo, "_proc_pax",
                               side_effect=AssertionError("over-budget metadata must not be parsed")) as parser:
            self.reject(header_only, limits=replace(limits, metadata_bytes=511))
            self.reject(header_only, limits=replace(limits, archive_headers=0))
            parser.assert_not_called()

    def test_duplicate_roots_and_excessive_metadata_nesting_are_rejected(self):
        self.reject(archive_bytes(member(".", kind=tarfile.DIRTYPE),
                                  member("./.", kind=tarfile.DIRTYPE)))
        prefix = raw_archive(member("././@LongLink", kind=tarfile.GNUTYPE_LONGNAME,
                                    data=b"name\0"))[:-1024]
        self.encode(prefix * encoder.MAX_METADATA_DEPTH + raw_archive(member("file")))
        self.reject(prefix * (encoder.MAX_METADATA_DEPTH + 1) + raw_archive(member("file")))

    def test_stream_mode_never_caches_tarinfo_members(self):
        data = archive_bytes(*(member(f"file{index}") for index in range(32)))
        original_next = tarfile.TarFile.next
        observations = []

        def next_without_cache(archive):
            result = original_next(archive)
            self.assertTrue(archive.stream)
            self.assertEqual([], archive.members)
            observations.append(len(archive.members))
            return result

        with mock.patch.object(tarfile.TarFile, "next", next_without_cache):
            _, result = self.encode(data)
        self.assertEqual(32, result["entryCount"])
        self.assertGreaterEqual(len(observations), 32)

    def test_uid_and_gid_bounds(self):
        for field in ("uid", "gid"):
            for value in (-1, 65536):
                with self.subTest(field=field, value=value):
                    self.reject(raw_archive(member(**{field: value})))
        self.encode(raw_archive(member(uid=65535, gid=65535)))
        self.reject(archive_bytes(member(pax={"uid": "not-an-integer"})))
        self.reject(archive_bytes(member(pax={"gid": "-1"})))

    def test_permission_bits_are_rejected_not_masked(self):
        for kind in (tarfile.DIRTYPE, tarfile.REGTYPE, tarfile.SYMTYPE, tarfile.LNKTYPE):
            for mode in (0o4755, 0o2755, 0o10000, -1):
                with self.subTest(kind=kind, mode=mode):
                    self.reject(raw_archive(member("entry", kind=kind, target="target"), mode=mode))
        for kind in (tarfile.REGTYPE, tarfile.SYMTYPE, tarfile.LNKTYPE):
            with self.subTest(sticky_non_directory=kind):
                self.reject(raw_archive(member("entry", kind=kind, target="target"), mode=0o1755))
        stream, _ = self.encode(raw_archive(member("dir", kind=tarfile.DIRTYPE), mode=0o1777))
        self.assertEqual(0o1777, self.decode(stream)[0][2])
        self.reject(raw_archive(member(".", kind=tarfile.DIRTYPE), mode=0o4755))

    def test_hardlinks_require_exact_preceding_regular_file(self):
        for target in ("future", "/file", "../file", "dir/../file", "./file", "file/", "", "file\0hidden"):
            with self.subTest(target=repr(target)):
                self.reject(archive_bytes(member("file", data=b"data"),
                                          member("link", kind=tarfile.LNKTYPE, target=target)))
        self.reject(archive_bytes(member("link", kind=tarfile.LNKTYPE, target="file"), member("file")))
        for kind in (tarfile.SYMTYPE, tarfile.DIRTYPE, tarfile.LNKTYPE):
            with self.subTest(non_regular=kind):
                self.reject(archive_bytes(member("file"), member("other", kind=kind, target="file"),
                                          member("link", kind=tarfile.LNKTYPE, target="other")))

    def test_non_file_data_is_rejected_including_skipped_root(self):
        for kind in (tarfile.DIRTYPE, tarfile.SYMTYPE, tarfile.LNKTYPE):
            with self.subTest(kind=kind):
                self.reject(raw_archive(member("entry", kind=kind, target="file", size=1)))
        self.reject(raw_archive(member(".", kind=tarfile.DIRTYPE, size=1)))
        self.reject(archive_bytes(member("dir", kind=tarfile.DIRTYPE, pax={"size": "1"})))

    def test_utf8_is_strict_and_limits_count_bytes(self):
        stream, _ = self.encode(archive_bytes(member("目录/文件", data=b"ok"),
                                             member("链接", kind=tarfile.SYMTYPE, target="../目标")))
        self.assertEqual(["目录/文件", "链接"], [record[1] for record in self.decode(stream)])
        self.reject(raw_archive(member(), raw_path=b"invalid\xff"))
        self.reject(raw_archive(member("link", kind=tarfile.SYMTYPE, target="invalid\udcff")))
        self.reject(archive_bytes(member("invalid\udcff")))
        self.reject(archive_bytes(member("文")), limits=replace(encoder.Limits(), path_bytes=2))
        self.reject(archive_bytes(member("x", kind=tarfile.SYMTYPE, target="文")),
                    limits=replace(encoder.Limits(), link_bytes=2))

    def test_path_and_target_frozen_length_limits(self):
        path = "/".join(["x" * 255] * 15 + ["y" * 254, "z"])
        self.assertEqual(4096, len(path.encode("utf-8")))
        self.assertTrue(all(len(part.encode("utf-8")) <= 255 for part in path.split("/")))
        stream, _ = self.encode(archive_bytes(member(path)))
        self.assertEqual(path, self.decode(stream)[0][1])
        oversized_path = path + "z"
        self.assertEqual(4097, len(oversized_path.encode("utf-8")))
        self.assertTrue(all(len(part.encode("utf-8")) <= 255 for part in oversized_path.split("/")))
        self.reject(archive_bytes(member(oversized_path)))
        self.encode(archive_bytes(member("link", kind=tarfile.SYMTYPE, target="x" * 4096)))
        self.reject(archive_bytes(member("link", kind=tarfile.SYMTYPE, target="x" * 4097)))
        self.reject(archive_bytes(member("link", kind=tarfile.SYMTYPE)))

    def test_path_component_utf8_limit_applies_to_entries_and_hardlink_targets(self):
        for path in ("x" * 255, "文" * 85):
            with self.subTest(valid_component=path[:1]):
                self.assertEqual(255, len(path.encode("utf-8")))
                stream, _ = self.encode(archive_bytes(member(path),
                                                     member("link", kind=tarfile.LNKTYPE, target=path)))
                self.assertEqual(path.encode("utf-8"), self.decode(stream)[1][-1])
        for path in ("x" * 256, "文" * 85 + "x"):
            with self.subTest(oversized_component=path[:1]):
                self.assertEqual(256, len(path.encode("utf-8")))
                self.reject(archive_bytes(member(path)))
                data = archive_bytes(member("file"), member("link", kind=tarfile.LNKTYPE, target=path))
                with self.assertRaisesRegex(encoder.EncodingError, "component"):
                    encoder.encode_archive(io.BytesIO(data), io.BytesIO())

    def test_tight_names_limit_counts_normalized_utf8_paths_and_terminators(self):
        data = archive_bytes(member(".", kind=tarfile.DIRTYPE), member("./a"),
                             member("文", kind=tarfile.SYMTYPE, target="/guest/target"))
        limits = replace(encoder.Limits(), names_bytes=6)
        _, result = self.encode(data, limits=limits)
        self.assertEqual(4, result["pathBytes"])
        self.assertEqual(6, result["namesBytes"])
        self.reject(data, limits=replace(limits, names_bytes=5))
        self.encode(archive_bytes(member("a"), member("b", kind=tarfile.LNKTYPE, target="a")),
                    limits=replace(limits, names_bytes=4))
        self.reject(archive_bytes(member("a")), limits=replace(limits, names_bytes=1))
        self.encode(archive_bytes(member(".", kind=tarfile.DIRTYPE)),
                    limits=replace(limits, names_bytes=0))

    def test_small_injected_entry_file_and_total_limits(self):
        limits = replace(encoder.Limits(), entries=2, file_bytes=3, total_file_bytes=5)
        self.encode(archive_bytes(member("a", data=b"123"), member("b", data=b"45")), limits=limits)
        self.reject(archive_bytes(member("a"), member("b"), member("c")), limits=limits)
        with mock.patch.object(tarfile.TarFile, "extractfile", side_effect=AssertionError("must reject before data")):
            self.reject(raw_archive(member("a", size=4)), limits=limits)
        self.reject(archive_bytes(member("a", data=b"123"), member("b", data=b"456")), limits=limits)
        self.reject(archive_bytes(member("a", data=b"1")), limits=replace(limits, total_file_bytes=0))
        self.encode(archive_bytes(member(".", kind=tarfile.DIRTYPE)), limits=replace(limits, entries=0))
        self.reject(archive_bytes(member("a")), limits=replace(limits, entries=0))

    def test_limits_cannot_relax_protocol_and_metadata_is_bounded(self):
        for field in ("entries", "file_bytes", "total_file_bytes", "path_bytes", "link_bytes",
                      "names_bytes", "archive_headers", "metadata_bytes"):
            for value in (-1, getattr(encoder.Limits(), field) + 1):
                with self.subTest(field=field, value=value):
                    self.reject(archive_bytes(), limits=replace(encoder.Limits(), **{field: value}))
        self.reject(raw_archive(member("pax", kind=tarfile.XHDTYPE, size=encoder.CHUNK_BYTES + 1)))

    def test_truncated_invalid_and_negative_file_data_are_rejected(self):
        self.reject(b"not a tar archive")
        self.reject(raw_archive(member("file", size=4096)))
        self.reject(raw_archive(member("file", size=-1)))
        self.reject(archive_bytes(member(pax={"size": "broken"})))

    def test_short_writes_work_and_failed_output_is_not_successful(self):
        class ShortWriter(io.BytesIO):
            def write(self, data):
                return super().write(data[:3])

        output = ShortWriter()
        source = io.BytesIO(archive_bytes(member("file", data=b"payload")))
        result = encoder.encode_archive(source, output)
        self.assertFalse(source.closed)
        self.assertFalse(output.closed)
        self.assertEqual(b"payload", self.decode(output.getvalue())[0][-1])
        self.assertEqual(len(output.getvalue()), result["streamBytes"])
        with self.assertRaises(encoder.EncodingError):
            encoder.encode_archive(io.BytesIO(archive_bytes()), mock.Mock(write=mock.Mock(return_value=None)))

    def test_real_fixed_asset_metadata_and_stream_counts(self):
        with encoder.SOURCE.open("rb") as source:
            output = io.BytesIO()
            result = encoder.encode_archive(source, output)
        self.assertEqual(encoder.EXPECTED_SOURCE_SHA, result["sourceSHA"])
        self.assertEqual(encoder.EXPECTED_SOURCE_BYTES, result["sourceBytes"])
        self.assertEqual(encoder.EXPECTED_ARCHIVE_COUNTS, result["archiveCounts"])
        self.assertEqual(encoder.EXPECTED_OWNERS, result["ownerCounts"])
        self.assertEqual(521, result["archiveEntryCount"])
        self.assertEqual(521, result["archiveHeaderCount"])
        self.assertEqual(0, result["metadataBytes"])
        self.assertEqual(8155613, result["streamBytes"])
        self.assertEqual("c91777d29b90113e55c60a989317e03103433521f1a7281619f5ca92af3226a0",
                         result["streamSHA"])
        self.assertEqual(521 - result["skippedRootDirectories"], result["entryCount"])
        records = self.decode(output.getvalue())
        self.assertEqual(result["entryCount"], len(records))
        self.assertEqual(result["namesBytes"], sum(len(record[1].encode("utf-8")) + 1 for record in records))
        self.assertEqual(9964, result["namesBytes"])
        for kind, label in ((1, "dir"), (2, "file"), (3, "symlink"), (4, "hardlink")):
            self.assertEqual(result["counts"][label], sum(record[0] == kind for record in records))
        self.assertEqual(result["fileDataBytes"], sum(len(record[-1]) for record in records if record[0] == 2))
        self.assertEqual(result["streamSHA"], hashlib.sha256(output.getvalue()).hexdigest())
        print(json.dumps({"fixedAsset": result}, sort_keys=True))


class PublicationTest(unittest.TestCase):
    def test_closed_temporary_file_is_published_atomically_without_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory) / "temporary"
            output = Path(directory) / "output"
            temporary.write_bytes(b"complete stream")
            encoder._commit_stream(temporary, output)
            self.assertEqual(b"complete stream", output.read_bytes())
            self.assertFalse(temporary.exists())
            self.assertEqual([output], list(Path(directory).iterdir()))
            inode = output.stat().st_ino
            temporary.write_bytes(b"complete stream")
            encoder._commit_stream(temporary, output)
            self.assertEqual(inode, output.stat().st_ino)
            temporary.unlink()
            self.assertEqual(b"complete stream", output.read_bytes())
            self.assertEqual([output], list(Path(directory).iterdir()))

    def test_different_existing_output_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory) / "temporary"
            output = Path(directory) / "output"
            temporary.write_bytes(b"new")
            output.write_bytes(b"old")
            with self.assertRaises(encoder.EncodingError):
                encoder._commit_stream(temporary, output)
            self.assertEqual(b"old", output.read_bytes())

    def test_concurrent_output_creation_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory) / "temporary"
            output = Path(directory) / "output"
            temporary.write_bytes(b"new")
            original_rename = encoder._rename_noreplace

            def race(*args, **kwargs):
                output.write_bytes(b"concurrent data")
                return original_rename(*args, **kwargs)

            with mock.patch.object(encoder, "_rename_noreplace", side_effect=race):
                with self.assertRaises(encoder.EncodingError):
                    encoder._commit_stream(temporary, output)
            self.assertEqual(b"concurrent data", output.read_bytes())

    def test_unsupported_atomic_primitive_fails_without_rename_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory) / "temporary"
            output = Path(directory) / "output"
            temporary.write_bytes(b"new")
            with mock.patch.object(encoder, "_rename_noreplace", side_effect=OSError("unsupported")):
                with self.assertRaises(OSError):
                    encoder._commit_stream(temporary, output)
            self.assertFalse(output.exists())
            self.assertEqual(b"new", temporary.read_bytes())

    def test_fixed_asset_preparation_only_in_a_temporary_output_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "stream"
            with mock.patch.object(encoder, "OUTPUT", output):
                result = encoder.prepare_rootfs()
                self.assertEqual(result["streamSHA"], hashlib.sha256(output.read_bytes()).hexdigest())
                inode = output.stat().st_ino
                self.assertEqual(result, encoder.prepare_rootfs())
                self.assertEqual(inode, output.stat().st_ino)
                output.write_bytes(b"someone else's data")
                with self.assertRaises(encoder.EncodingError):
                    encoder.prepare_rootfs()
                self.assertEqual(b"someone else's data", output.read_bytes())
                self.assertEqual([output], list(Path(directory).iterdir()))

    def test_unknown_fixed_asset_is_rejected_before_parser_or_temporary_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            output = Path(directory) / "stream"
            for data in (b"not a tar", bytes(encoder.EXPECTED_SOURCE_BYTES)):
                with self.subTest(source_bytes=len(data)):
                    source.write_bytes(data)
                    with mock.patch.object(encoder, "SOURCE", source), mock.patch.object(encoder, "OUTPUT", output):
                        with mock.patch.object(encoder, "encode_archive",
                                               side_effect=AssertionError("unknown source must not be parsed")) as parser:
                            with mock.patch.object(encoder.tempfile, "mkstemp",
                                                   side_effect=AssertionError("verification must precede output")) as create:
                                with self.assertRaisesRegex(encoder.EncodingError, "Fixed asset"):
                                    encoder.prepare_rootfs()
                                parser.assert_not_called()
                                create.assert_not_called()
                    self.assertEqual([source], list(Path(directory).iterdir()))

    def test_fixed_source_verification_rewinds_and_reuses_the_same_open_file(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "stream"
            original_verify = encoder._verify_fixed_source
            original_encode = encoder.encode_archive
            verified = []

            def verify(source):
                original_verify(source)
                self.assertEqual(0, source.tell())
                verified.append(source)

            def encode(source, destination):
                self.assertIs(verified[0], source)
                self.assertEqual(0, source.tell())
                return original_encode(source, destination)

            with mock.patch.object(encoder, "OUTPUT", output):
                with mock.patch.object(encoder, "_verify_fixed_source", side_effect=verify):
                    with mock.patch.object(encoder, "encode_archive", side_effect=encode):
                        encoder.prepare_rootfs()
            self.assertTrue(verified[0].closed)
            self.assertEqual([output], list(Path(directory).iterdir()))

    def test_failure_closes_and_removes_only_owned_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "stream"
            with mock.patch.object(encoder, "OUTPUT", output):
                with mock.patch.object(encoder, "encode_archive", side_effect=encoder.EncodingError("failure")):
                    with self.assertRaises(encoder.EncodingError):
                        encoder.prepare_rootfs()
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_cli_help_and_extra_paths_do_not_prepare_anything(self):
        with mock.patch.object(encoder, "prepare_rootfs") as prepare:
            with contextlib.redirect_stdout(io.StringIO()) as output:
                with self.assertRaises(SystemExit) as error:
                    encoder.main(["--help"])
            self.assertEqual(0, error.exception.code)
            self.assertIn("fixed bundled Alpine", output.getvalue())
            with contextlib.redirect_stderr(io.StringIO()):
                for arguments in (["input.tar", "output"], ["--input", "anything"], ["--exec", "anything"]):
                    with self.subTest(arguments=arguments):
                        with self.assertRaises(SystemExit) as error:
                            encoder.main(arguments)
                        self.assertEqual(2, error.exception.code)
            prepare.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
