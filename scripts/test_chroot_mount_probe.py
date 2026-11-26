import json
import os
from pathlib import Path
import subprocess
import unittest

import test_chroot_probe as common


ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / "app/build/reports/chroot-runtime/mount-probe/mount-probe"
NONCE = "1234567890abcdef12345678"
common.PROBE = PROBE


class MountProbeArgumentTest(unittest.TestCase):
    def reject(self, *arguments):
        result = subprocess.run([str(PROBE), *arguments], capture_output=True, text=True,
                                timeout=3, env={"PATH": os.defpath, "LANG": "C"})
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        event = json.loads(result.stdout)
        self.assertFalse(event["passed"])
        self.assertEqual("not_created", event["cleanup_state"])
        self.assertIsNone(event["created_directory"])

    def test_help_is_read_only(self):
        result = subprocess.run([str(PROBE), "--help"], capture_output=True, text=True, timeout=3)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("--exercise", result.stdout)

    def test_missing_mode_or_nonce_is_rejected(self):
        self.reject()
        self.reject("--exercise")

    def test_unknown_interfaces_are_rejected(self):
        for argument in ("--shell", "--exec", "--mount", "--inspect", "--chroot"):
            with self.subTest(argument=argument):
                self.reject(argument)

    def test_extra_arguments_are_rejected(self):
        self.reject("--help", "extra")
        self.reject("--exercise", NONCE, "extra")

    def test_invalid_nonce_cannot_select_an_existing_path(self):
        for nonce in ("", "0" * 23, "0" * 25, "A" * 24, "z" * 24, "../" * 8,
                      "/data/local", "0" * 23 + "\n"):
            with self.subTest(nonce=repr(nonce)):
                self.reject("--exercise", nonce)


if __name__ == "__main__":
    suite = unittest.TestSuite([
        unittest.defaultTestLoader.loadTestsFromTestCase(MountProbeArgumentTest),
        unittest.defaultTestLoader.loadTestsFromTestCase(common.ChrootProbeElfTest),
    ])
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
