"""test-summary.py가 가짜 통과를 내지 않는지 검사하는 harness 자체 시험 (HARNESS-001)."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/test-summary.py"
NOT_BEFORE = "2026-10-03T12:00:00Z"
FRESH = "2026-10-03T12:00:05"
STALE = "2026-10-03T11:59:59"


def testcases(tests: int, failures: int = 0, errors: int = 0, skipped: int = 0) -> str:
    """suite 합계와 일치하는 <testcase> 자식(앞에서부터 failure → error → skipped 표시)."""
    marks = ["<failure/>"] * failures + ["<error/>"] * errors + ["<skipped/>"] * skipped
    marks += [""] * (tests - len(marks))
    return "".join(f'<testcase name="t{i}">{mark}</testcase>' for i, mark in enumerate(marks[:tests]))


def report(tests=2, failures=0, errors=0, skipped=0, timestamp=FRESH, cases=None) -> str:
    body = testcases(tests, failures, errors, skipped) if cases is None else cases
    return (f'<?xml version="1.0"?><testsuite name="S" tests="{tests}" failures="{failures}" '
            f'errors="{errors}" skipped="{skipped}" timestamp="{timestamp}">{body}</testsuite>')


class TestSummaryGate(unittest.TestCase):

    def run_summary(self, reports: list[str], min_tests: int = 2, with_not_before: bool = True):
        with tempfile.TemporaryDirectory() as d:
            for i, content in enumerate(reports):
                Path(d, f"TEST-s{i}.xml").write_text(content, encoding="utf-8")
            argv = [sys.executable, str(SCRIPT)]
            if with_not_before:
                argv += ["--not-before", NOT_BEFORE]
            argv += [str(min_tests), d]
            return subprocess.run(argv, capture_output=True, text=True)

    def test_fresh_passing_reports_pass(self):
        result = self.run_summary([report()])
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("PASS", result.stdout)

    def test_missing_not_before_is_rejected(self):
        self.assertNotEqual(self.run_summary([report()], with_not_before=False).returncode, 0)

    def test_no_reports_fail(self):
        self.assertEqual(self.run_summary([]).returncode, 1)

    def test_zero_tests_fail(self):
        self.assertEqual(self.run_summary([report(tests=0)], min_tests=1).returncode, 1)

    def test_below_min_tests_fail(self):
        self.assertEqual(self.run_summary([report(tests=1)], min_tests=2).returncode, 1)

    def test_failures_fail(self):
        self.assertEqual(self.run_summary([report(failures=1)]).returncode, 1)

    def test_errors_fail(self):
        self.assertEqual(self.run_summary([report(errors=1)]).returncode, 1)

    def test_skips_fail(self):
        self.assertEqual(self.run_summary([report(skipped=1)]).returncode, 1)

    def test_stale_report_fails_even_if_another_is_fresh(self):
        self.assertEqual(self.run_summary([report(), report(timestamp=STALE)]).returncode, 1)

    def test_report_without_timestamp_fails(self):
        no_ts = '<?xml version="1.0"?><testsuite name="S" tests="2" failures="0" errors="0" skipped="0"></testsuite>'
        self.assertEqual(self.run_summary([no_ts]).returncode, 1)

    # --- Vitest JUnit 형식(ADMIN-BOOT-001 S1, Q-S0-3 판정 A): 루트 <testsuites>에는 timestamp·skipped가 없고
    # 파일별 자식 <testsuite>에만 있다(S0 실측 §17.4). 판정 기준은 그대로이고 자식 suite 단위로 읽는다.

    def test_vitest_testsuites_root_with_fresh_suites_passes(self):
        result = self.run_summary([vitest_report([suite(tests=2), suite(tests=1)])], min_tests=3)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("total tests=3 failures=0 errors=0 skipped=0", result.stdout)

    def test_vitest_child_skip_fails_although_root_has_no_skipped_attribute(self):
        # it.todo·it.skip은 자식 suite의 skipped로만 집계된다. 루트만 읽으면 0으로 보여 통과한다(구멍).
        result = self.run_summary([vitest_report([suite(tests=2), suite(tests=3, skipped=2)])], min_tests=2)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("skipped=2", result.stdout)

    def test_vitest_child_failure_fails(self):
        self.assertEqual(self.run_summary([vitest_report([suite(tests=2, failures=1)])]).returncode, 1)

    def test_vitest_stale_child_fails_even_if_another_child_is_fresh(self):
        result = self.run_summary([vitest_report([suite(tests=2), suite(tests=1, timestamp=STALE)])])
        self.assertEqual(result.returncode, 1, result.stdout)

    def test_vitest_child_without_timestamp_fails(self):
        self.assertEqual(self.run_summary([vitest_report([suite(tests=2), suite(tests=1, timestamp=None)])]).returncode, 1)

    def test_vitest_root_without_suites_fails_even_with_zero_minimum(self):
        # 시험 0건(passWithNoTests:false여도 Vitest는 빈 <testsuites>를 쓴다)은 하한과 무관하게 성공이 아니다.
        empty = '<?xml version="1.0"?><testsuites name="vitest tests" tests="0" failures="0" errors="0" time="0"></testsuites>'
        self.assertEqual(self.run_summary([empty], min_tests=0).returncode, 1)

    def test_gradle_and_vitest_reports_are_summed_against_minimum(self):
        reports = [report(tests=2), vitest_report([suite(tests=2), suite(tests=1)])]
        self.assertEqual(self.run_summary(reports, min_tests=5).returncode, 0)
        self.assertEqual(self.run_summary(reports, min_tests=6).returncode, 1)

    # --- suite 합계와 <testcase> 대조(ADMIN-BOOT-001 S1 리뷰 n1): 합계 속성만 믿으면 보고서가 skip·실패를 숨길 수 있다.

    def test_suite_tests_attribute_disagreeing_with_testcases_fails(self):
        self.assertEqual(self.run_summary([report(tests=3, cases=testcases(2))], min_tests=2).returncode, 1)

    def test_testcase_failure_hidden_by_suite_attribute_fails(self):
        self.assertEqual(self.run_summary([report(tests=2, cases=testcases(2, failures=1))]).returncode, 1)

    def test_testcase_error_hidden_by_suite_attribute_fails(self):
        self.assertEqual(self.run_summary([report(tests=2, cases=testcases(2, errors=1))]).returncode, 1)

    def test_testcase_skip_hidden_by_suite_attribute_fails(self):
        result = self.run_summary([report(tests=2, cases=testcases(2, skipped=1))])
        self.assertEqual(result.returncode, 1, result.stdout)

    def test_vitest_child_with_hidden_skipped_testcase_fails(self):
        hidden = ('<?xml version="1.0"?><testsuites name="vitest tests" tests="2" failures="0" errors="0" time="0">'
                  f'<testsuite name="a" timestamp="2026-10-03T12:00:05.000Z" tests="2" failures="0" errors="0" skipped="0">'
                  f'{testcases(2, skipped=1)}</testsuite></testsuites>')
        self.assertEqual(self.run_summary([hidden]).returncode, 1)

    def test_unknown_root_element_fails(self):
        # 신선한 timestamp·건수 속성이 있어도 <testsuite>/<testsuites>가 아니면 판정 대상이 아니다.
        unknown = (f'<?xml version="1.0"?><results tests="5" failures="0" errors="0" skipped="0" '
                   f'timestamp="{FRESH}"/>')
        self.assertEqual(self.run_summary([unknown], min_tests=0).returncode, 1)


def suite(tests=2, failures=0, errors=0, skipped=0, timestamp="2026-10-03T12:00:05.123Z") -> dict:
    return {"tests": tests, "failures": failures, "errors": errors, "skipped": skipped, "timestamp": timestamp}


def vitest_report(suites: list[dict]) -> str:
    """Vitest 5 junit reporter 모양: 루트 합계에는 skipped·timestamp가 없다."""
    def total(key):
        return sum(s[key] for s in suites)
    children = "".join(
        f'<testsuite name="src/f{i}.test.ts"' + (f' timestamp="{s["timestamp"]}"' if s["timestamp"] else "")
        + f' hostname="h" tests="{s["tests"]}" failures="{s["failures"]}" errors="{s["errors"]}"'
        f' skipped="{s["skipped"]}" time="0.01">{testcases(s["tests"], s["failures"], s["errors"], s["skipped"])}</testsuite>'
        for i, s in enumerate(suites))
    return (f'<?xml version="1.0" encoding="UTF-8" ?><testsuites name="vitest tests" tests="{total("tests")}" '
            f'failures="{total("failures")}" errors="{total("errors")}" time="0.02">{children}</testsuites>')


if __name__ == "__main__":
    unittest.main()
