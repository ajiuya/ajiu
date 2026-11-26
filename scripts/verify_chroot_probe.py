from pathlib import Path
import argparse
import hashlib
import json
import struct


ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_elf(data):
    require(len(data) >= 64 and data[:7] == b"\x7fELF\x02\x01\x01", "Expected little-endian ELF64")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    require(elf_type == 3, "Expected position-independent ET_DYN executable")
    require(machine == 183, "Expected AArch64 machine")
    program_offset = struct.unpack_from("<Q", data, 32)[0]
    program_size, program_count = struct.unpack_from("<HH", data, 54)
    require(program_size == 56 and program_count > 0, "Invalid program header size")
    require(program_offset >= 64 and program_offset + program_size * program_count <= len(data),
            "Program headers exceed file bounds")
    segments = [struct.unpack_from("<IIQQQQQQ", data, program_offset + index * program_size)
                for index in range(program_count)]
    require(all(segment[2] + segment[5] <= len(data) for segment in segments), "Segment exceeds file bounds")
    require(all(segment[0] != 3 for segment in segments), "Dynamic interpreter present")
    loads = [segment for segment in segments if segment[0] == 1]
    require(bool(loads) and all(segment[7] >= 16384 and segment[7] & (segment[7] - 1) == 0 for segment in loads),
            "Invalid LOAD alignment or alignment below 16 KiB")
    require(all((segment[2] - segment[3]) % segment[7] == 0 for segment in loads), "LOAD offset is not congruent")
    require(all(segment[1] & 3 != 3 for segment in loads), "Writable and executable LOAD segment")
    stacks = [segment for segment in segments if segment[0] == 0x6474E551]
    require(len(stacks) == 1 and stacks[0][1] & 1 == 0, "Executable or missing GNU_STACK")
    require(any(segment[0] == 0x6474E552 for segment in segments), "GNU_RELRO missing")
    dynamic = [segment for segment in segments if segment[0] == 2]
    require(len(dynamic) == 1 and dynamic[0][5] % 16 == 0, "Invalid dynamic segment")
    segment = dynamic[0]
    tags = []
    terminated = False
    for offset in range(segment[2], segment[2] + segment[5], 16):
        tag, value = struct.unpack_from("<qQ", data, offset)
        if tag == 0:
            terminated = True
            break
        tags.append((tag, value))
    require(terminated, "Dynamic segment has no terminal tag")
    require(not any(tag == 1 for tag, _ in tags), "DT_NEEDED dependency present")
    require(any(tag == 0x6FFFFFFB and value & 0x08000000 for tag, value in tags), "PIE dynamic flag missing")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--kind", choices=("cgroup", "mount", "rootfs", "guest"), default="cgroup")
    kind = parser.parse_args().kind
    report_name, binary_name, source_name = {
        "cgroup": ("probe", "cgroup-probe", "cgroup_probe.c"),
        "mount": ("mount-probe", "mount-probe", "mount_probe.c"),
        "rootfs": ("rootfs-install", "rootfs-install", "rootfs_install.c"),
        "guest": ("rootfs-probe", "rootfs-probe", "rootfs_probe.c"),
    }[kind]
    output = ROOT / "app/build/reports/chroot-runtime" / report_name
    binary = output / binary_name
    source = ROOT / "native/chroot" / source_name
    data = binary.read_bytes()
    verify_elf(data)
    result = {
        "architecture": "aarch64",
        "format": "ELF64 little-endian ET_DYN static PIE",
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "hasDynamicInterpreter": False,
        "hasDynamicDependencies": False,
        "loadAlignmentAtLeast16KiB": True,
        "writableExecutableLoad": False,
        "nonExecutableStack": True,
        "relro": True,
        "androidExecutionVerified": False,
    }
    (output / "elf-verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
