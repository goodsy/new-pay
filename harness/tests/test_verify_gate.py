"""verify-gate.py가 가짜 통과·무한 대기를 내지 않는지 검사하는 harness 자체 시험 (HARNESS-001).

ROOT는 스크립트 위치로 정해지므로 임시 트리에 복사하고 stub commands.json으로 실행한다.
"""
import json
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/verify-gate.py"
PY = sys.executable
FP_A = "sha256:" + "a" * 64
FP_B = "sha256:" + "b" * 64


def fingerprint_stub(path="fp.txt"):
    return [PY, "-c", f"print('headRevision=abc'); print('dirty=false'); print('codeFingerprint=' + open('{path}').read().strip())"]


class VerifyGate(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        target = self.root / "harness/scripts/verify-gate.py"
        target.parent.mkdir(parents=True)
        shutil.copy(SCRIPT, target)
        (self.root / "fp.txt").write_text(FP_A, encoding="utf-8")
        self.commands = {
            "FINGERPRINT": {"argv": fingerprint_stub()},
            "HARNESS-SELFTEST": {"argv": [PY, "-c", "print('Ran 3 tests'); print('OK')"],
                                 "outputMustMatch": ["^OK$"], "outputMustNotMatch": ["skipped="],
                                 "countRegex": r"^Ran (\d+) tests?", "minTests": 3},
            "GRADLE-CHECK": {"argv": [PY, "-c", "print('> Task :test')"],
                             "outputMustNotMatch": ["FROM-CACHE"]},
            "TEST-SUMMARY": {"argv": [PY, "-c", "import sys; print('nb=' + sys.argv[1])", "NOT_BEFORE_UTC"]},
            "IMAGE-DIGESTS": {"argv": [PY, "-c", "print('sha256:x')"]},
        }

    def gate(self, *extra, timeout=60):
        registry = {"commands": [{"id": k, "timeoutSeconds": 20, **v} for k, v in self.commands.items()]}
        (self.root / "harness/commands.json").write_text(json.dumps(registry), encoding="utf-8")
        started = time.monotonic()
        proc = subprocess.run([PY, str(self.root / "harness/scripts/verify-gate.py"), "T-1", *extra],
                              capture_output=True, text=True, timeout=timeout)
        elapsed = time.monotonic() - started
        evidence = None
        for line in proc.stdout.splitlines():
            if line.startswith("evidence="):
                evidence = json.loads((self.root / line.split("=", 1)[1]).read_text(encoding="utf-8"))
        return proc.returncode, evidence, elapsed

    def test_all_steps_pass(self):
        code, ev, _ = self.gate()
        self.assertEqual((code, ev["verdict"]), (0, "PASS"), ev)
        self.assertEqual(len(ev["runs"]), 6)

    def test_not_before_is_substituted_with_gradle_check_start(self):
        _, ev, _ = self.gate()
        summary = next(r for r in ev["runs"] if r["commandId"] == "TEST-SUMMARY")
        self.assertIn(ev["notBefore"], summary["argv"])
        self.assertNotIn("NOT_BEFORE_UTC", summary["argv"])
        self.assertIn("nb=" + ev["notBefore"], summary["outputTail"])

    def test_failing_step_stops_gate(self):
        self.commands["GRADLE-CHECK"] = {"argv": [PY, "-c", "raise SystemExit(1)"]}
        code, ev, _ = self.gate()
        self.assertEqual((code, ev["verdict"]), (1, "FAIL"))
        self.assertEqual(ev["runs"][-1]["commandId"], "GRADLE-CHECK")

    def test_timeout_is_failure_and_bounded(self):
        self.commands["GRADLE-CHECK"] = {"argv": [PY, "-c", "import time; time.sleep(60)"], "timeoutSeconds": 1}
        code, ev, elapsed = self.gate()
        self.assertEqual((code, ev["verdict"]), (1, "FAIL"))
        self.assertTrue(ev["runs"][-1]["timedOut"])
        self.assertLess(elapsed, 30)

    def test_fingerprint_change_during_gate_fails(self):
        self.commands["GRADLE-CHECK"] = {"argv": [PY, "-c", f"open('fp.txt','w').write('{FP_B}')"]}
        code, ev, _ = self.gate()
        self.assertEqual((code, ev["verdict"]), (1, "FAIL"))
        self.assertIn("fingerprint changed during gate", ev["problems"])

    def test_missing_fingerprint_output_fails(self):
        self.commands["FINGERPRINT"] = {"argv": [PY, "-c", "print('headRevision=abc')"]}
        code, ev, _ = self.gate()
        self.assertEqual((code, ev["verdict"]), (1, "FAIL"))
        self.assertIn("fingerprint missing or malformed", ev["problems"])

    def test_expected_fingerprint_mismatch_fails(self):
        code, ev, _ = self.gate("--expect-fingerprint", FP_B)
        self.assertEqual((code, ev["verdict"]), (1, "FAIL"))

    def test_expected_fingerprint_match_passes(self):
        code, ev, _ = self.gate("--expect-fingerprint", FP_A)
        self.assertEqual((code, ev["verdict"]), (0, "PASS"))

    def test_cached_test_output_fails(self):
        self.commands["GRADLE-CHECK"]["argv"] = [PY, "-c", "print('> Task :test FROM-CACHE')"]
        code, ev, _ = self.gate()
        self.assertEqual((code, ev["verdict"]), (1, "FAIL"))

    def test_selftest_with_skips_fails(self):
        self.commands["HARNESS-SELFTEST"]["argv"] = [PY, "-c", "print('Ran 3 tests'); print('OK (skipped=1)')"]
        code, ev, _ = self.gate()
        self.assertEqual((code, ev["verdict"]), (1, "FAIL"))

    def test_selftest_below_min_count_fails(self):
        self.commands["HARNESS-SELFTEST"]["argv"] = [PY, "-c", "print('Ran 2 tests'); print('OK')"]
        code, ev, _ = self.gate()
        self.assertEqual((code, ev["verdict"]), (1, "FAIL"))

    def test_missing_executable_is_environment_error(self):
        self.commands["IMAGE-DIGESTS"] = {"argv": ["paycore-no-such-binary"]}
        code, ev, _ = self.gate()
        self.assertEqual((code, ev["verdict"]), (3, "ENVIRONMENT_ERROR"))
        self.assertIsNotNone(ev["runs"][-1]["environmentError"])

    def test_invalid_task_id_is_rejected(self):
        proc = subprocess.run([PY, str(self.root / "harness/scripts/verify-gate.py"), "bad id;rm"],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 2)


if __name__ == "__main__":
    unittest.main()
