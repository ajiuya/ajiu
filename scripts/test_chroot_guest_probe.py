import json
import os
from pathlib import Path
import subprocess
import unittest

import test_chroot_probe as common


ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / "app/build/reports/chroot-runtime/rootfs-probe/rootfs-probe"
NONCE = "1234567890abcdef12345678"
common.PROBE = PROBE


class GuestProbeArgumentTest(unittest.TestCase):
    def rejected(self, arguments):
        result = subprocess.run([str(PROBE), *arguments], capture_output=True, text=True,
                                timeout=3, env={"PATH": os.defpath, "LANG": "C"})
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        event = json.loads(result.stdout)
        self.assertFalse(event["passed"])
        self.assertEqual("invalid_arguments", event["error_code"])
        self.assertEqual("not_created", event["cleanup_state"])

    def test_help_is_read_only(self):
        result = subprocess.run([str(PROBE), "--help"], capture_output=True, text=True, timeout=3)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("fixed literals only", result.stdout)
        self.assertIn("--network", result.stdout)
        self.assertIn("--pty", result.stdout)

    def test_unknown_and_incomplete_interfaces_are_rejected(self):
        for arguments in ([], ["--exec"], ["--shell"], ["--mount"], ["--exercise"], ["--help", "extra"]):
            self.rejected(arguments)

    def test_extra_arguments_are_rejected(self):
        for mode in ("--exercise", "--network", "--pty"):
            with self.subTest(mode=mode):
                self.rejected([mode, NONCE, "65098", "8525637", "10445", "10445", "extra"])
                self.rejected([mode])

    def test_invalid_nonce_is_rejected(self):
        for nonce in ("", "0" * 23, "0" * 25, "A" * 24, "../" * 8, "/data/local", "x" * 24):
            for mode in ("--exercise", "--network", "--pty"):
                with self.subTest(nonce=nonce, mode=mode):
                    self.rejected([mode, nonce, "65098", "8525637", "10445", "10445"])

    def test_instance_identity_requires_strict_nonzero_decimal(self):
        for identity in ("", "0", "-1", "+1", "01", "1x", "18446744073709551616"):
            for index in (2, 3):
                with self.subTest(identity=identity, index=index):
                    arguments = ["--exercise", NONCE, "65098", "8525637", "10445", "10445"]
                    arguments[index] = identity
                    self.rejected(arguments)

    def test_uid_gid_must_be_same_unprivileged_identity(self):
        for uid, gid in (("0", "0"), ("1000", "1000"), ("9999", "9999"), ("2147483648", "2147483648"),
                         ("10445", "0"), ("10445", "10446"), ("10445x", "10445x"), ("-1", "-1")):
            with self.subTest(uid=uid, gid=gid):
                self.rejected(["--exercise", NONCE, "65098", "8525637", uid, gid])


if __name__ == "__main__":
    suite = unittest.TestSuite([
        unittest.defaultTestLoader.loadTestsFromTestCase(GuestProbeArgumentTest),
        unittest.defaultTestLoader.loadTestsFromTestCase(common.ChrootProbeElfTest),
    ])
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
