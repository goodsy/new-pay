"""ACQUIRING-001 E2E 관찰 스크립트(harness/e2e/acquiring_e2e.py)의 실행 없는 단위 시험.

Demo 스택·docker를 쓰지 않는다: argv 구조(비밀 값 없음)·입력 검증·SQL 템플릿 입력 고정·시나리오별 기대 판정만 본다. 실제 E2E 실행은 Coordinator가 Demo 스택에서 한다.
"""
import importlib.util
import os
import unittest
from datetime import date

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SPEC = importlib.util.spec_from_file_location("acquiring_e2e", os.path.join(ROOT, "harness", "e2e", "acquiring_e2e.py"))
e2e = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(e2e)


class AcquiringE2eScriptTest(unittest.TestCase):
    def test_argv_is_a_fixed_list_and_never_carries_the_password_value(self):
        argv = e2e.mysql_argv("paycore-demo-mysql-1", "SELECT 1")
        self.assertIsInstance(argv, list)
        self.assertEqual(argv[:5], ["docker", "exec", "-e", "MYSQL_PWD", "paycore-demo-mysql-1"])
        # -e MYSQL_PWD는 값 없이(호출 환경에서 전달) — '=' 없음, 비밀번호 플래그(-p) 없음.
        self.assertFalse(any(a.startswith("MYSQL_PWD=") or a.startswith("-p") for a in argv))

    def test_invalid_arguments_are_rejected_before_anything_runs(self):
        good = ["--scenario", "normal", "--acquirer", "SIMACQA", "--business-date", "2026-11-05", "--amount", "12000"]
        self.assertEqual(e2e.parse_args(good).acquirer, "SIMACQA")
        for bad in (
            ["--acquirer", "SIMACQA'; DROP TABLE x; --"], ["--acquirer", "simacqa"], ["--business-date", "2026-13-01"], ["--amount", "0"],
            ["--scenario", "other"], ["--container", "x y"], ["--timeout-seconds", "5"],
        ):
            args = list(good)
            if bad[0] in args:
                args[args.index(bad[0]) + 1] = bad[1]
            else:
                args += bad
            with self.assertRaises(SystemExit):
                e2e.parse_args(args)

    def test_sql_templates_only_take_validated_values(self):
        q = e2e.queries("SIMACQA", date(2026, 11, 5), 12000)
        self.assertIn("file_id = 'ACQ.SIMACQA.TEST.20261105.001'", q["file"])
        self.assertIn("record_amount_minor = 12000", q["results"])
        # 따옴표 안 값은 검증된 입력(매입사·업무일·파일 ID)과 고정 상수뿐이다.
        literals = set()
        for sql in q.values():
            parts = sql.split("'")
            self.assertEqual(len(parts) % 2, 1, sql)
            literals.update(parts[1::2])
        self.assertEqual(literals, {"SIMACQA", "TEST", "+00:00", "+09:00", "2026-11-05", "ACQ.SIMACQA.TEST.20261105.001", ""})

    def test_expectations_per_scenario(self):
        f = ["TRANSMITTED", "RECEIVED", "COMPLETE", "1"]
        self.assertTrue(e2e.expectation_met("normal", f, [["ptx_1", "1", "ACCEPTED", "", "RESULT_FILE"]]))
        self.assertFalse(e2e.expectation_met("normal", ["SEALED", "NONE", "AWAITING", "1"], [["ptx_1", "1", "ACCEPTED", "", "RESULT_FILE"]]))
        self.assertTrue(e2e.expectation_met("reject", f, [["ptx_1", "1", "REJECTED", "DEMO_REJECTED", "RESULT_FILE"]]))
        self.assertFalse(e2e.expectation_met("reject", f, [["ptx_1", "1", "ACCEPTED", "", "RESULT_FILE"]]))
        late = [["ptx_1", "1", "UNKNOWN", "", "DEADLINE"], ["ptx_1", "2", "ACCEPTED", "", "RESULT_FILE"]]
        self.assertTrue(e2e.expectation_met("missing-result", ["TRANSMITTED", "RECEIVED", "COMPLETE", "1"], late))
        # UNKNOWN만 있으면(늦은 결과 전) 아직 아니다, UNKNOWN 없이 ACCEPTED면 시나리오가 재현되지 않은 것이다.
        self.assertFalse(e2e.expectation_met("missing-result", f, late[:1]))
        self.assertFalse(e2e.expectation_met("missing-result", f, late[1:]))


if __name__ == "__main__":
    unittest.main()
