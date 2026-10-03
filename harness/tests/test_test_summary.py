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


def report(tests=2, failures=0, errors=0, skipped=0, timestamp=FRESH) -> str:
    return (f'<?xml version="1.0"?><testsuite name="S" tests="{tests}" failures="{failures}" '
            f'errors="{errors}" skipped="{skipped}" timestamp="{timestamp}"></testsuite>')


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


if __name__ == "__main__":
    unittest.main()
