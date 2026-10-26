"""verify-gate.py가 가짜 통과·무한 대기를 내지 않는지 검사하는 harness 자체 시험 (HARNESS-001).

ROOT는 스크립트 위치로 정해지므로 임시 트리에 복사하고 stub commands.json으로 실행한다.
"""
import hashlib
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
            "TEST-SUMMARY-FRONTEND": {"argv": [PY, "-c", "import sys; print('fnb=' + sys.argv[1])", "NOT_BEFORE_UTC"]},
            "IMAGE-DIGESTS": {"argv": [PY, "-c", "print('sha256:x')"]},
        }

    def gate(self, *extra, timeout=60, env=None):
        registry = {"commands": [{"id": k, "timeoutSeconds": 20, **v} for k, v in self.commands.items()]}
        (self.root / "harness/commands.json").write_text(json.dumps(registry), encoding="utf-8")
        if not extra:
            extra = ("--expect-fingerprint", FP_A)
        started = time.monotonic()
        proc = subprocess.run([PY, str(self.root / "harness/scripts/verify-gate.py"), "T-1", *extra],
                              capture_output=True, text=True, timeout=timeout, env=env)
        elapsed = time.monotonic() - started
        evidence = None
        for line in proc.stdout.splitlines():
            if line.startswith("evidence="):
                evidence = json.loads((self.root / line.split("=", 1)[1]).read_text(encoding="utf-8"))
        return proc.returncode, evidence, elapsed

    def test_all_steps_pass(self):
        code, ev, _ = self.gate()
        self.assertEqual((code, ev["verdict"]), (0, "PASS"), ev)
        self.assertEqual([r["commandId"] for r in ev["runs"]],
                         ["FINGERPRINT", "HARNESS-SELFTEST", "GRADLE-CHECK", "TEST-SUMMARY", "TEST-SUMMARY-FRONTEND",
                          "IMAGE-DIGESTS", "FINGERPRINT"])

    def test_frontend_summary_uses_the_same_not_before(self):
        # ADMIN-BOOT-001 S1 리뷰 M2(a): 프론트 보고서는 별도 하한으로 판정하되 신선도 기준은 GRADLE-CHECK 시작 시각 하나다.
        _, ev, _ = self.gate()
        frontend = next(r for r in ev["runs"] if r["commandId"] == "TEST-SUMMARY-FRONTEND")
        self.assertIn(ev["notBefore"], frontend["argv"])
        self.assertNotIn("NOT_BEFORE_UTC", frontend["argv"])
        self.assertIn("fnb=" + ev["notBefore"], frontend["outputTail"])

    def test_failing_frontend_summary_fails_gate(self):
        self.commands["TEST-SUMMARY-FRONTEND"] = {"argv": [PY, "-c", "raise SystemExit(1)"]}
        code, ev, _ = self.gate()
        self.assertEqual((code, ev["verdict"]), (1, "FAIL"))
        self.assertEqual(ev["runs"][-1]["commandId"], "TEST-SUMMARY-FRONTEND")

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
        self.assertEqual(len(ev["runs"]), 1)

    def test_missing_fingerprint_on_final_stage_fails(self):
        # 첫 단계는 정상, 마지막 FINGERPRINT 직전에 출력이 사라지는 경우
        self.commands["IMAGE-DIGESTS"] = {"argv": [PY, "-c", "open('fp.txt','w').write('garbage')"]}
        code, ev, _ = self.gate()
        self.assertEqual((code, ev["verdict"]), (1, "FAIL"))
        self.assertIn("fingerprint missing or malformed", ev["problems"])

    def test_expected_fingerprint_mismatch_fails_fast(self):
        code, ev, _ = self.gate("--expect-fingerprint", FP_B)
        self.assertEqual((code, ev["verdict"]), (1, "FAIL"))
        self.assertEqual([r["commandId"] for r in ev["runs"]], ["FINGERPRINT"])

    def test_expected_fingerprint_is_required(self):
        registry = {"commands": [{"id": k, "timeoutSeconds": 20, **v} for k, v in self.commands.items()]}
        (self.root / "harness/commands.json").write_text(json.dumps(registry), encoding="utf-8")
        proc = subprocess.run([PY, str(self.root / "harness/scripts/verify-gate.py"), "T-1"],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 2)

    def test_escaped_descendant_holding_pipe_is_bounded(self):
        # 새 session으로 빠져나간 손자 프로세스가 stdout pipe를 잡은 채 남는 경우
        grandchild = ("import subprocess, sys, time; "
                      "subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(25)'], start_new_session=True); "
                      "time.sleep(60)")
        self.commands["GRADLE-CHECK"] = {"argv": [PY, "-c", grandchild], "timeoutSeconds": 1}
        code, ev, elapsed = self.gate()
        self.assertEqual((code, ev["verdict"]), (1, "FAIL"))
        self.assertTrue(ev["runs"][-1]["timedOut"])
        self.assertTrue(ev["runs"][-1]["cleanupUnverified"])
        self.assertLess(elapsed, 40)

    def test_archive_globs_copy_reports_into_evidence(self):
        report = self.root / "out/TEST-x.xml"
        report.parent.mkdir()
        report.write_text("<testsuite/>", encoding="utf-8")
        self.commands["GRADLE-CHECK"]["archiveGlobs"] = ["out/TEST-*.xml"]
        code, ev, _ = self.gate()
        self.assertEqual(code, 0)
        archived = next(r for r in ev["runs"] if r["commandId"] == "GRADLE-CHECK")["archived"]
        self.assertEqual(len(archived), 1)
        self.assertTrue((self.root / archived[0]["copy"]).is_file())

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

    def test_npm_environment_inputs_are_recorded_by_name_without_values(self):
        # ADMIN-BOOT-001 S0 §17.7: npm_config_*(대소문자 무관)·~/.npmrc는 npm ci의 registry·스크립트 실행을 바꾼다.
        # 판정에 영향을 줄 수 있는 저장소 밖 입력이므로 기록하되, 토큰일 수 있는 값은 남기지 않는다(이름·파일 hash만).
        home = self.root / "home"
        home.mkdir()
        (home / ".npmrc").write_text("//registry.example.invalid/:_authToken=NPMRC-SECRET\n", encoding="utf-8")
        env = {"PATH": "/usr/bin:/bin", "HOME": str(home),
               "npm_config_registry": "https://ENV-SECRET-REGISTRY.invalid/",
               "NPM_CONFIG_SCRIPT_SHELL": "/tmp/ENV-SECRET-SHELL", "npm_package_name": "not-a-config"}
        code, ev, _ = self.gate(env=env)
        self.assertEqual(code, 0, ev)
        snapshot = ev["environment"]
        self.assertEqual(snapshot["npmEnvNames"], ["NPM_CONFIG_SCRIPT_SHELL", "npm_config_registry"])
        # 값의 무염 hash도 남기지 않는다(registry URL·짧은 토큰은 추정 가능).
        self.assertFalse([n for n in snapshot["envSha256"] if n.lower().startswith("npm_config_")])
        self.assertEqual(snapshot["userFiles"]["~/.npmrc"],
                         hashlib.sha256((home / ".npmrc").read_bytes()).hexdigest())
        text = json.dumps(ev)
        for secret in ("ENV-SECRET-REGISTRY", "ENV-SECRET-SHELL", "NPMRC-SECRET"):
            self.assertNotIn(secret, text)

    def test_npm_environment_absent_is_recorded_as_empty(self):
        home = self.root / "home"
        home.mkdir()
        code, ev, _ = self.gate(env={"PATH": "/usr/bin:/bin", "HOME": str(home)})
        self.assertEqual(code, 0, ev)
        self.assertEqual(ev["environment"]["npmEnvNames"], [])
        self.assertNotIn("~/.npmrc", ev["environment"]["userFiles"])

    def test_invalid_task_id_is_rejected(self):
        proc = subprocess.run([PY, str(self.root / "harness/scripts/verify-gate.py"), "bad id;rm"],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 2)


if __name__ == "__main__":
    unittest.main()
