import errno
import json
from pathlib import Path
import struct
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
BINARY = ROOT / "app/build/reports/chroot-runtime/rootfs-install/rootfs-install"
HEADER = b"AJIF\x00\x01\x00\x00"
END = struct.pack(">BBHIIIQ", 255, 0, 0, 0, 0, 0, 0)


def record(kind, path, data=b"", mode=0o755, uid=0, gid=0):
    path = path.encode() if isinstance(path, str) else path
    return struct.pack(">BBHIIIQ", kind, 0, len(path), mode, uid, gid, len(data)) + path + data


class RootfsStreamTest(unittest.TestCase):
    def run_stream(self, stream, valid):
        result = subprocess.run([str(BINARY), "--validate"], input=stream,
                                capture_output=True, timeout=3)
        event = json.loads(result.stdout)
        self.assertEqual(0 if valid else 3, result.returncode, event)
        self.assertEqual(valid, event["passed"])
        self.assertEqual("not_created", event["cleanup_state"])
        self.assertIsNone(event["created_directory"])
        return event

    def test_empty_valid_stream(self):
        self.run_stream(HEADER + END, True)

    def test_files_and_guest_symlink_targets(self):
        data = record(1, "bin") + record(2, "bin/tool", b"fixed-data") + record(3, "bin/link", b"/bin/tool")
        event = self.run_stream(HEADER + data + END, True)
        self.assertEqual(3, event["entries"])
        self.assertEqual(10, event["file_bytes"])

    def test_directory_sticky_and_backward_hardlink(self):
        self.run_stream(HEADER + record(1, "tmp", mode=0o1777) +
                        record(2, "a", b"a") + record(4, "b", b"a") + END, True)

    def test_invalid_paths_and_utf8(self):
        for path in (b"/etc/passwd", b"../sentinel", b"a/../b", b"a//b", b"./a", b"a/", b"a\x00b", b"\xff", b"a" * 256):
            with self.subTest(path=repr(path)):
                self.run_stream(HEADER + record(1, path) + END, False)

    def test_duplicate_paths(self):
        self.run_stream(HEADER + record(1, "a") + record(1, "a") + END, False)

    def test_privileged_modes_and_ids(self):
        for kind, mode, uid, gid in ((2, 0o4755, 0, 0), (1, 0o2775, 0, 0), (2, 0o1777, 0, 0),
                                     (1, 0o755, 65536, 0), (1, 0o755, 0, 65536)):
            with self.subTest(kind=kind, mode=mode, uid=uid, gid=gid):
                self.run_stream(HEADER + record(kind, "bad", mode=mode, uid=uid, gid=gid) + END, False)

    def test_hardlink_cannot_target_forward_directory_symlink_or_escape(self):
        for prefix, target in ((b"", b"future"), (record(1, "dir"), b"dir"),
                               (record(3, "link", b"/bin/sh"), b"link"), (b"", b"../x"), (b"", b"/x")):
            with self.subTest(target=target):
                self.run_stream(HEADER + prefix + record(4, "bad", target) + END, False)

    def test_truncated_and_trailing_data(self):
        self.run_stream(HEADER + record(1, "dir") + END + b"extra", False)
        self.run_stream(HEADER + END[:-1], False)
        self.run_stream(HEADER + struct.pack(">BBHIIIQ", 2, 0, 1, 0o644, 0, 0, 10) + b"a" + b"123", False)

    def test_special_entries_and_directory_data(self):
        for kind, data in ((5, b""), (0, b""), (1, b"unexpected")):
            with self.subTest(kind=kind):
                self.run_stream(HEADER + record(kind, "bad", data) + END, False)

    def test_large_record_rejected_before_reading_data(self):
        stream = HEADER + struct.pack(">BBHIIIQ", 2, 0, 1, 0o644, 0, 0, 128 * 1024 * 1024 + 1)
        self.run_stream(stream, False)

    def test_open_input_timeout_returns_json_instead_of_signal_exit(self):
        for prefix in (b"", HEADER[:3], HEADER):
            with self.subTest(prefix=prefix):
                with subprocess.Popen([str(BINARY), "--validate"], stdin=subprocess.PIPE,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
                    process.stdin.write(prefix)
                    process.stdin.flush()
                    self.assertEqual(3, process.wait(timeout=35))
                    event = json.loads(process.stdout.read())
                    self.assertFalse(event["passed"])
                    self.assertEqual(errno.ETIMEDOUT, event["errno"])
                    self.assertEqual("not_created", event["cleanup_state"])
                    self.assertIsNone(event["created_directory"])

    def test_arguments_rejected_without_install(self):
        for arguments in ([], ["--exec"], ["--install"], ["--install", "../bad"],
                          ["--install", "0" * 24, "extra"], ["--remove", "0" * 24, "-1", "1"]):
            with self.subTest(arguments=arguments):
                result = subprocess.run([str(BINARY), *arguments], capture_output=True, timeout=3)
                self.assertEqual(2, result.returncode)
                self.assertIsNone(json.loads(result.stdout)["created_directory"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
