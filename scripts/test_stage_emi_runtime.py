import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "stage_emi_runtime.sh"


class StageEmiRuntimeTests(unittest.TestCase):
    def run_stage(
        self,
        exit_codes: list[int],
        version: str | None = "1.1.24+1.21.1",
        runtime_version: str | None = "5sIPA1To",
    ):
        self.assertTrue(SCRIPT.exists(), "missing scripts/stage_emi_runtime.sh")
        with tempfile.TemporaryDirectory() as temp_dir:
            project = Path(temp_dir)
            invocations = project / "invocations.jsonl"
            attempts = project / "attempts.txt"
            gradlew = project / "gradlew"
            gradlew.write_text(
                "#!/usr/bin/env python3\n"
                "import json\n"
                "import os\n"
                "from pathlib import Path\n"
                "import sys\n"
                "attempts = Path(os.environ['FAKE_ATTEMPTS'])\n"
                "count = int(attempts.read_text()) if attempts.exists() else 0\n"
                "attempts.write_text(str(count + 1))\n"
                "with Path(os.environ['FAKE_INVOCATIONS']).open('a') as output:\n"
                "    output.write(json.dumps(sys.argv[1:]) + '\\n')\n"
                "codes = [int(value) for value in os.environ['FAKE_EXIT_CODES'].split(',')]\n"
                "sys.exit(codes[min(count, len(codes) - 1)])\n"
            )
            gradlew.chmod(gradlew.stat().st_mode | stat.S_IXUSR)
            command = ["bash", str(SCRIPT)]
            if version is not None:
                command.append(version)
                if runtime_version is not None:
                    command.append(runtime_version)
            environment = os.environ.copy()
            environment.update(
                {
                    "FAKE_ATTEMPTS": str(attempts),
                    "FAKE_INVOCATIONS": str(invocations),
                    "FAKE_EXIT_CODES": ",".join(str(code) for code in exit_codes),
                }
            )
            result = subprocess.run(
                command,
                cwd=project,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            calls = []
            if invocations.exists():
                calls = [json.loads(line) for line in invocations.read_text().splitlines()]
            return result, calls

    def test_successful_stage_runs_once(self):
        result, calls = self.run_stage([0])

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(calls))

    def test_transient_failure_retries_the_exact_command_once(self):
        result, calls = self.run_stage([9, 0])

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(calls))
        self.assertEqual(calls[0], calls[1])
        self.assertEqual(
            [
                "stageEmiRuntime",
                "-Pemi_version=1.1.24+1.21.1",
                "-Pemi_runtime_version=5sIPA1To",
                "--console=plain",
                "--no-daemon",
            ],
            calls[0],
        )
        self.assertIn("retrying the same Gradle command once", result.stderr)

    def test_second_failure_is_reported_without_more_attempts(self):
        result, calls = self.run_stage([9, 7])

        self.assertEqual(7, result.returncode)
        self.assertEqual(2, len(calls))

    def test_missing_version_fails_before_gradle(self):
        result, calls = self.run_stage([0], version=None)

        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)
        self.assertIn(
            "usage: stage_emi_runtime.sh <emi-version> <modrinth-version-id>",
            result.stderr,
        )

    def test_missing_runtime_version_fails_before_gradle(self):
        result, calls = self.run_stage([0], runtime_version=None)

        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)
        self.assertIn(
            "usage: stage_emi_runtime.sh <emi-version> <modrinth-version-id>",
            result.stderr,
        )


if __name__ == "__main__":
    unittest.main()
