import os
import json
from pathlib import Path
import subprocess
import struct
import sys
import tempfile
import unittest

from verify_chroot_probe import verify_elf


ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / "app/build/reports/chroot-runtime/probe/cgroup-probe"
NONCE = "1234567890abcdef12345678"


class ChrootProbeArgumentTest(unittest.TestCase):
    def rejected(self, *arguments):
        result = subprocess.run(
            [str(PROBE), *arguments], capture_output=True, text=True, timeout=3,
            env={"PATH": os.defpath, "LANG": "C"},
        )
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        event = json.loads(result.stdout)
        self.assertEqual("invalid_arguments", event["error_code"])
        self.assertEqual("arguments", event["stage"])
        self.assertEqual("not_created", event["cleanup_state"])
        self.assertIsNone(event["created_group"])

    def test_help_is_read_only_and_successful(self):
        result = subprocess.run([str(PROBE), "--help"], capture_output=True, text=True, timeout=3)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("--inspect", result.stdout)
        self.assertIn("--exercise", result.stdout)

    def test_no_mode_and_unknown_modes_are_rejected(self):
        self.rejected()
        for argument in ("--exec", "--mount", "--chroot", "--shell", "--inspect-extra"):
            with self.subTest(argument=argument):
                self.rejected(argument)

    def test_extra_arguments_are_rejected(self):
        self.rejected("--help", "extra")
        self.rejected("--inspect", "extra")
        self.rejected("--exercise", NONCE, "10445", "10445", "3003", "baseline", "extra")

    def test_exercise_requires_all_arguments(self):
        arguments = ("--exercise", NONCE, "10445", "10445", "3003", "baseline")
        for size in range(1, len(arguments)):
            with self.subTest(size=size):
                self.rejected(*arguments[:size])

    def test_nonce_cannot_select_an_existing_path(self):
        for nonce in ("", "0" * 23, "0" * 25, "A" * 24, "z" * 24, "../" * 8, "/sys/fs/cgroup/apps", "0" * 23 + "\n"):
            with self.subTest(nonce=repr(nonce)):
                self.rejected("--exercise", nonce, "10445", "10445", "3003", "baseline")

    def test_privileged_or_malformed_uids_are_rejected(self):
        for uid in ("0", "1000", "2000", "9999", "-1", "+10445", "10445x", "4294967296", ""):
            with self.subTest(uid=uid):
                self.rejected("--exercise", NONCE, uid, uid, "3003", "baseline")

    def test_gid_must_match_the_selected_app_uid(self):
        for gid in ("0", "1000", "2000", "10446", "10445x", ""):
            with self.subTest(gid=gid):
                self.rejected("--exercise", NONCE, "10445", gid, "3003", "baseline")

    def test_privileged_and_malformed_groups_are_rejected(self):
        for groups in ("0", "1000", "2000", "3003,0", "3003,1000", "3003,2000", ",3003", "3003,", "3003,,9997", "3003x", "+3003", "-1", ""):
            with self.subTest(groups=groups):
                self.rejected("--exercise", NONCE, "10445", "10445", groups, "baseline")

    def test_unknown_exercise_modes_are_rejected(self):
        for mode in ("", "exec", "mount", "kill", "baseline-extra", "../apps"):
            with self.subTest(mode=mode):
                self.rejected("--exercise", NONCE, "10445", "10445", "3003", mode)


class ChrootProbeElfTest(unittest.TestCase):
    def test_static_pie_passes_elf_checks(self):
        verify_elf(PROBE.read_bytes())

    def test_invalid_headers_are_rejected(self):
        original = PROBE.read_bytes()
        invalid = [b"", original[:63], b"wrong!!" + original[7:]]
        for offset, format_, value in ((16, "<H", 2), (18, "<H", 62), (32, "<Q", len(original))):
            changed = bytearray(original)
            struct.pack_into(format_, changed, offset, value)
            invalid.append(changed)
        for changed in invalid:
            with self.subTest(size=len(changed)):
                with self.assertRaises(ValueError):
                    verify_elf(changed)

    def test_interpreter_alignment_stack_and_relro_are_checked(self):
        original = PROBE.read_bytes()
        offset = struct.unpack_from("<Q", original, 32)[0]
        size, count = struct.unpack_from("<HH", original, 54)
        headers = [offset + index * size for index in range(count)]
        load = next(at for at in headers if struct.unpack_from("<I", original, at)[0] == 1)
        stack = next(at for at in headers if struct.unpack_from("<I", original, at)[0] == 0x6474E551)
        relro = next(at for at in headers if struct.unpack_from("<I", original, at)[0] == 0x6474E552)
        for at, format_, value in ((load, "<I", 3), (load + 48, "<Q", 4096),
                                  (load + 4, "<I", 7), (stack + 4, "<I", 7), (relro, "<I", 0)):
            changed = bytearray(original)
            struct.pack_into(format_, changed, at, value)
            with self.subTest(offset=at, value=value):
                with self.assertRaises(ValueError):
                    verify_elf(changed)

    def test_dynamic_dependencies_are_rejected(self):
        changed = bytearray(PROBE.read_bytes())
        offset = struct.unpack_from("<Q", changed, 32)[0]
        size, count = struct.unpack_from("<HH", changed, 54)
        dynamic = next(offset + index * size for index in range(count)
                       if struct.unpack_from("<I", changed, offset + index * size)[0] == 2)
        data_offset = struct.unpack_from("<Q", changed, dynamic + 8)[0]
        struct.pack_into("<qQ", changed, data_offset, 1, 0)
        with self.assertRaises(ValueError):
            verify_elf(changed)

    def test_optimized_python_cannot_skip_rejection(self):
        invalid = bytearray(PROBE.read_bytes())
        struct.pack_into("<H", invalid, 18, 62)
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "invalid-architecture"
            binary.write_bytes(invalid)
            code = "from verify_chroot_probe import verify_elf; from pathlib import Path; import sys; verify_elf(Path(sys.argv[1]).read_bytes())"
            result = subprocess.run([sys.executable, "-O", "-c", code, str(binary)],
                                    cwd=ROOT / "scripts", capture_output=True, text=True, timeout=3)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Expected AArch64 machine", result.stderr)


if __name__ == "__main__":
    unittest.main()
