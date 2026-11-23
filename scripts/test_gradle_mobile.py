#!/usr/bin/env python3

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().with_name("gradle-mobile.sh")
DEFAULT_OPTIONS = [
    "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=512m -XX:+UseParallelGC",
    "--max-workers=1",
    "--no-parallel",
    "--no-daemon",
    "--no-configuration-cache",
    "-Pkotlin.compiler.execution.strategy=in-process",
    "-PmobileBuild=true",
    "-PmobileTestHeap=1g",
]
PROTECTED_PROPERTIES = [
    "org.gradle.jvmargs",
    "org.gradle.workers.max",
    "org.gradle.parallel",
    "org.gradle.daemon",
    "org.gradle.configuration-cache",
    "kotlin.compiler.execution.strategy",
    "mobileBuild",
    "mobileTestHeap",
]


class GradleMobileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="gradle mobile tests ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "project with spaces"
        (self.root / "scripts").mkdir(parents=True)
        self.script = self.root / "scripts" / SCRIPT.name
        shutil.copyfile(SCRIPT, self.script)
        self.script.chmod(0o755)
        self.capture = Path(self.temp.name) / "capture.json"
        self.outside = Path(self.temp.name) / "outside project"
        self.outside.mkdir()
        self.wrapper = self.root / "gradlew"
        self.wrapper.write_text(
            "#!/usr/bin/env python3\n"
            "import json, os, sys\n"
            "from pathlib import Path\n"
            "Path(os.environ['FAKE_GRADLE_CAPTURE']).write_text(json.dumps({\n"
            "    'args': sys.argv[1:], 'cwd': os.getcwd(), 'pid': os.getpid()\n"
            "}), encoding='utf-8')\n"
            "sys.exit(int(os.environ.get('FAKE_GRADLE_EXIT', '0')))\n",
            encoding="utf-8",
        )
        self.wrapper.chmod(0o755)

    def invoke(self, args=(), env=None):
        if self.capture.exists():
            self.capture.unlink()
        child_env = os.environ.copy()
        for key in ("MOBILE_GRADLE_HEAP", "MOBILE_TEST_HEAP", "FAKE_GRADLE_EXIT"):
            child_env.pop(key, None)
        child_env["FAKE_GRADLE_CAPTURE"] = str(self.capture)
        child_env.update(env or {})
        process = subprocess.Popen(
            [str(self.script), *args],
            cwd=self.outside,
            env=child_env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.addCleanup(lambda: process.poll() is None and process.kill())
        stdout, stderr = process.communicate(timeout=10)
        payload = json.loads(self.capture.read_text(encoding="utf-8")) if self.capture.exists() else None
        return process.returncode, stdout, stderr, payload, process.pid

    def assert_rejected(self, args=(), env=None):
        code, stdout, stderr, payload, _ = self.invoke(args, env)
        self.assertEqual(code, 2, stderr)
        self.assertEqual(stdout, "")
        self.assertIn("gradle-mobile:", stderr)
        self.assertIsNone(payload, "被拒绝的参数不能调用 gradlew")

    def test_defaults_and_project_root_from_another_directory(self):
        args = [":app:assembleUniversalDebug", "--offline"]
        code, _, stderr, payload, _ = self.invoke(args)
        self.assertEqual(code, 0, stderr)
        self.assertEqual(payload["cwd"], str(self.root.resolve()))
        self.assertEqual(payload["args"], args + DEFAULT_OPTIONS)

    def test_forwards_spaces_unicode_and_shell_characters_without_evaluation(self):
        marker = self.outside / "unexpected execution"
        args = [
            ":app:testUniversalDebugUnitTest",
            "--tests", "com.example.Test with spaces",
            "-Pnote=中文 参数 'quote' \"double\" ; *",
            f"-Pexpression=$(touch '{marker}')",
            "-PappVersionName=1.13.0", "-PappVersionCode=1184",
            "--project-prop", "otherProperty=value with spaces",
            "--system-prop=otherSystemProperty=value with spaces",
            "--no-daemon", "--no-parallel", "--no-configuration-cache",
        ]
        code, _, stderr, payload, _ = self.invoke(args)
        self.assertEqual(code, 0, stderr)
        self.assertEqual(payload["args"], args + DEFAULT_OPTIONS)
        self.assertFalse(marker.exists())

    def test_heap_environment_overrides_only_the_heap_options(self):
        code, _, stderr, payload, _ = self.invoke(
            [":app:testUniversalDebugUnitTest"],
            {"MOBILE_GRADLE_HEAP": "2048M", "MOBILE_TEST_HEAP": "768m"},
        )
        self.assertEqual(code, 0, stderr)
        expected = DEFAULT_OPTIONS.copy()
        expected[0] = "-Dorg.gradle.jvmargs=-Xmx2048M -XX:MaxMetaspaceSize=512m -XX:+UseParallelGC"
        expected[-1] = "-PmobileTestHeap=768m"
        self.assertEqual(payload["args"], [":app:testUniversalDebugUnitTest"] + expected)
        code, _, stderr, payload, _ = self.invoke()
        self.assertEqual(code, 0, stderr)
        self.assertEqual(payload["args"], DEFAULT_OPTIONS)

    def test_accepts_positive_integer_megabytes_or_gigabytes(self):
        for value in ("1m", "512M", "1g", "3G", "3072m"):
            with self.subTest(value=value):
                code, _, stderr, payload, _ = self.invoke(
                    env={"MOBILE_GRADLE_HEAP": value, "MOBILE_TEST_HEAP": value}
                )
                self.assertEqual(code, 0, stderr)
                self.assertIn(f"-PmobileTestHeap={value}", payload["args"])
                self.assertIn(f"-Xmx{value} ", payload["args"][0])

    def test_rejects_invalid_or_injected_heap_values_before_calling_gradle(self):
        marker = self.outside / "heap injection"
        invalid = (
            "", "0g", "-1g", "+1g", "01g", "1.5g", "1024", "1k", "1gb",
            " 1g", "1g ", "1g\n", "1g\n2g", "１g", "1g -XX:MaxMetaspaceSize=4g",
            f"$(touch '{marker}')", f"1g; touch '{marker}'",
        )
        for variable in ("MOBILE_GRADLE_HEAP", "MOBILE_TEST_HEAP"):
            for value in invalid:
                with self.subTest(variable=variable, value=value):
                    self.assert_rejected(env={variable: value})
        self.assertFalse(marker.exists())

    def test_rejects_conflicting_flags_and_project_redirection(self):
        cases = [
            ["--daemon"], ["--daemon=true"], ["--foreground"], ["--stop"],
            ["--parallel"], ["--parallel=true"], ["--configuration-cache"],
            ["--configuration-cache=true"], ["--max-workers=8"], ["--max-workers", "8"],
            ["--"], ["--", "--daemon"], ["-p", "other project"], ["-pother"],
            ["--project-dir=other"], ["--project-dir", "other"],
            ["-b", "other.gradle"], ["-bother.gradle"], ["--build-file=other.gradle"],
            ["-c", "settings.gradle"], ["-csettings.gradle"], ["--settings-file=other"],
            ["--daem"], ["--par"], ["--configuration-c"], ["--max-w=4"], ["--sto"],
            ["--project-d=other"], ["--project-pro", "mobileBuild=false"],
            ["--system-pro", "org.gradle.jvmargs=-Xmx8g"],
        ]
        for args in cases:
            with self.subTest(args=args):
                self.assert_rejected(args)

    def test_rejects_reserved_properties_in_every_supported_cli_form(self):
        for name in PROTECTED_PROPERTIES:
            for property_name in (name, "org.gradle.project." + name):
                value = property_name + "=override with spaces"
                cases = [
                    ["-P" + value], ["-D" + value], ["-P", value], ["-D", value],
                    ["--project-prop=" + value], ["--system-prop=" + value],
                    ["--project-prop", value], ["--system-prop", value],
                ]
                for args in cases:
                    with self.subTest(args=args):
                        self.assert_rejected(args)

    def test_rejects_missing_property_values(self):
        for option in ("-P", "-D", "--project-prop", "--system-prop"):
            for suffix in ([], [""], ["--daemon"]):
                with self.subTest(option=option, suffix=suffix):
                    self.assert_rejected([option, *suffix])

    def test_exec_keeps_pid_and_propagates_gradle_exit_status(self):
        code, _, stderr, payload, pid = self.invoke(env={"FAKE_GRADLE_EXIT": "29"})
        self.assertEqual(code, 29, stderr)
        self.assertEqual(payload["pid"], pid)


if __name__ == "__main__":
    unittest.main(verbosity=2)
