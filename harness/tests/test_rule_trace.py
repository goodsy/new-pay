"""rule-trace.py 시험 (RULE-TRACE-001, RULE-GOV-001 §2.3, D-GOV-006 ③ 경고 모드).

실제 대장이 아니라 harness/fixtures/rule-trace/clean(작은 가짜 저장소)을 임시 디렉터리에 복사하고 시험마다 위반을 넣어 검사한다.
fixture의 BR-ID는 9xx(실제 대장에 없는 번호)라 실제 저장소 실행의 '대체 규칙 시험 잔존' 검사에 섞이지 않는다.
"""
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HARNESS = Path(__file__).resolve().parents[1]
SCRIPT = HARNESS / "scripts/rule-trace.py"
FIXTURE = HARNESS / "fixtures/rule-trace/clean"
HEADER = "| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |\n|---|---|---|---|---|---|---|\n"


class RuleTrace(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.root = self.tmp / "repo"
        shutil.copytree(FIXTURE, self.root)

    def add_rows(self, domain, *rows):
        """대장의 '## 진단' 바로 앞(규칙 표 범위 안)에 새 절과 표를 넣는다."""
        path = self.root / f"docs/rules/{domain}.md"
        text = path.read_text(encoding="utf-8")
        block = "\n## 9. 시험 추가 절\n\n" + HEADER + "".join(r + "\n" for r in rows)
        path.write_text(text.replace("\n## 진단", block + "\n## 진단", 1), encoding="utf-8")

    def run_trace(self, *extra):
        out = self.tmp / "out.json"
        proc = subprocess.run([sys.executable, "-B", str(SCRIPT), "--root", str(self.root), "--json", str(out), *extra],
                              capture_output=True, text=True, timeout=60)
        report = json.loads(out.read_text(encoding="utf-8")) if out.is_file() else None
        return proc.returncode, proc.stdout + proc.stderr, report

    # --- 정상 ---------------------------------------------------------------------------------------------------

    def test_clean_fixture_has_no_warnings_and_exit_0(self):
        code, out, report = self.run_trace()
        self.assertEqual(code, 0, out)
        self.assertEqual(report["mode"], "warn")
        self.assertEqual(report["rows"], 16)  # BIZ 6 + PAY 2 + CAN·FIN·WH·ADM 각 2, 진단 절 행 제외
        for check in ("format", "evidenceMissing", "evidenceUnparsed", "taskRefs", "confirmedWithoutTest",
                      "supersededInTests"):
            self.assertEqual(report["checks"][check]["count"], 0, (check, report["checks"][check]))
        self.assertIn("rule-trace", out)
        self.assertIn("확정 규칙 시험 연결 없음: 0", out)

    def test_status_variants_are_normalized(self):
        _, _, report = self.run_trace()
        self.assertEqual(report["statusCounts"], {"확정": 7, "제안": 1, "Demo값": 1, "대체됨": 5, "미결": 2})
        notes = {n["id"]: n["note"] for n in report["checks"]["statusVariants"]["items"]}
        self.assertIn("BR-BIZ-905", notes)  # 미결(Q-…) 뒤 꼬리 메모
        self.assertIn("BR-BIZ-906", notes)  # 미결(… 후보)

    def test_pipe_inside_code_span_is_one_cell(self):
        _, _, report = self.run_trace()
        self.assertNotIn("BR-PAY-901", [i["id"] for i in report["checks"]["format"]["items"]])

    # --- 1) 형식 ------------------------------------------------------------------------------------------------

    def test_format_violations_are_listed(self):
        self.add_rows("BIZ",
                      "| BR-BIZ-910 | 열이 6개. | 확정 | 사용자 | D-T-001 | 없음 |",
                      "| BR-BIZ-901 | 중복 ID. | 제안 | 적용 | D-T-001 | X | 없음 |",
                      "| BR-PAY-911 | 파일 도메인과 다른 접두. | 제안 | 적용 | D-T-001 | X | 없음 |",
                      "| BR-BIZ-912 | 모르는 상태. | 승인됨 | 사용자 | D-T-001 | X | 없음 |",
                      "| BR-BIZ-913 | 모르는 출처. | 제안 | 추측 | D-T-001 | X | 없음 |",
                      "| BR-BIZ-914 | 대체 대상 없음. | 대체됨→BR-BIZ-990 | 사용자 | D-T-001 | X | 없음 |",
                      "| BR-BIZ-915 | 확정인데 출처 —. | 확정 | — | D-T-001 | X | AlphaSpec::a |")
        code, out, report = self.run_trace()
        self.assertEqual(code, 0, out)  # 경고 모드
        problems = {(i["id"], i["kind"]) for i in report["checks"]["format"]["items"]}
        self.assertEqual(problems, {("BR-BIZ-910", "COLUMNS"), ("BR-BIZ-901", "DUPLICATE_ID"),
                                    ("BR-PAY-911", "DOMAIN_PREFIX"), ("BR-BIZ-912", "UNKNOWN_STATUS"),
                                    ("BR-BIZ-913", "UNKNOWN_SOURCE"), ("BR-BIZ-914", "SUPERSEDED_TARGET_MISSING"),
                                    ("BR-BIZ-915", "SOURCE_STATUS_MISMATCH")})
        self.assertEqual(report["checks"]["format"]["count"], 7)

    def test_rows_in_diagnosis_section_are_ignored(self):
        _, _, report = self.run_trace()
        ids = [r for r in report["ruleIds"] if r.endswith("-999")]
        self.assertEqual(ids, [])

    # --- 2) 근거 ID ---------------------------------------------------------------------------------------------

    def test_missing_and_unparsed_evidence_ids(self):
        self.add_rows("CAN",
                      "| BR-CAN-910 | 없는 결정. | 제안 | 적용 | D-T-404, Q-T-404 권장안 | X | 없음 |",
                      "| BR-CAN-911 | 이어 쓴 번호. | 제안 | 적용 | D-T-001·004 ③ | X | 없음 |",
                      "| BR-CAN-912 | 범위 표기. | 제안 | 적용 | D-T-002~005 | X | 없음 |",
                      "| BR-CAN-913 | 자리표시·계열·문서 안 번호. | 제안 | 적용 | D-ID·Q-ID 미정, D-T 선례, BOOT1 D-08 | X | 없음 |",
                      "| BR-CAN-914 | 상태의 질문 ID. | 미결(Q-T-405) | — | 문서 | X | 없음 |")
        code, _, report = self.run_trace()
        self.assertEqual(code, 0)
        missing = {(i["id"], i["ref"]) for i in report["checks"]["evidenceMissing"]["items"]}
        self.assertEqual(missing, {("BR-CAN-910", "D-T-404"), ("BR-CAN-910", "Q-T-404"), ("BR-CAN-911", "D-T-004"),
                                   ("BR-CAN-912", "D-T-004"), ("BR-CAN-912", "D-T-005"), ("BR-CAN-914", "Q-T-405")})
        unparsed = {(i["id"], i["token"]) for i in report["checks"]["evidenceUnparsed"]["items"]}
        self.assertEqual(unparsed, {("BR-CAN-913", "D-ID"), ("BR-CAN-913", "Q-ID"), ("BR-CAN-913", "D-T"),
                                    ("BR-CAN-913", "D-08")})

    def test_candidate_question_in_status_is_not_missing(self):
        _, _, report = self.run_trace()  # BR-BIZ-906 미결(Q-T-NEW 후보)
        self.assertNotIn("Q-T-NEW", [i["ref"] for i in report["checks"]["evidenceMissing"]["items"]])

    # --- 3) 작업 카드 참조 ----------------------------------------------------------------------------------------

    def test_task_card_references_to_unconfirmed_or_unknown_rules(self):
        tasks = self.root / "harness/tasks.json"
        doc = json.loads(tasks.read_text(encoding="utf-8"))
        doc["tasks"].append({"id": "T-GATE", "spec": "BR-BIZ-903·BR-BIZ-901 참조",
                             "openItems": ["BR-BIZ-905 대기", "BR-WH-990 없는 규칙"]})
        tasks.write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
        code, _, report = self.run_trace()
        self.assertEqual(code, 0)
        refs = {(i["task"], i["field"], i["ruleId"], i["status"]) for i in report["checks"]["taskRefs"]["items"]}
        self.assertEqual(refs, {("T-GATE", "spec", "BR-BIZ-903", "제안"), ("T-GATE", "openItems", "BR-BIZ-905", "미결"),
                                ("T-GATE", "openItems", "BR-WH-990", "UNKNOWN")})

    # --- 4) 확정 규칙 시험 연결 ------------------------------------------------------------------------------------

    def test_confirmed_rules_without_existing_tests(self):
        self.add_rows("FIN",
                      "| BR-FIN-910 | 시험 없음. | 확정 | 사용자 | D-T-001 | X | 없음 |",
                      "| BR-FIN-911 | 없는 시험 파일. | 확정 | 사용자 | D-T-001 | X | MissingSpec(M)::a, AlphaSpec::b |",
                      "| BR-FIN-912 | 없는 프런트 시험. | 확정 | 사용자 | D-T-001 | X | Nope.test.tsx(F)::a |",
                      "| BR-FIN-913 | 시험 이름을 읽을 수 없음. | 확정 | 사용자 | D-T-001 | X | 수동 확인 |",
                      "| BR-FIN-914 | 제안은 대상 아님. | 제안 | 적용 | D-T-001 | X | 없음 |",
                      # '::' 뒤는 시험 안 이름(Given 이름·대문자 낱말)이라 파일로 찾지 않는다
                      "| BR-FIN-915 | 시험 안 이름. | 확정 | 사용자 | D-T-001 | X | "
                      "`AlphaSpec::NestedGivenSpec CREDIT·CHECK 밖`, 둘째 설명; GammaSpec(A)(표 단위 비교) |")
        code, out, report = self.run_trace()
        self.assertEqual(code, 0)
        check = report["checks"]["confirmedWithoutTest"]
        reasons = {i["id"]: (i["reason"], i["missing"]) for i in check["items"]}
        self.assertEqual(reasons, {"BR-FIN-910": ("NONE", []), "BR-FIN-911": ("NOT_FOUND", ["MissingSpec"]),
                                   "BR-FIN-912": ("NOT_FOUND", ["Nope.test.tsx"]), "BR-FIN-913": ("UNRECOGNIZED", [])})
        self.assertEqual(check["byDomain"]["FIN"], 4)
        self.assertEqual(check["byDomain"]["BIZ"], 0)
        self.assertIn("확정 규칙 시험 연결 없음: 4", out)

    # --- 5) 대체 규칙 시험 잔존 ------------------------------------------------------------------------------------

    def test_superseded_rule_id_left_in_test_source(self):
        spec = self.root / "apps/alpha-api/src/test/kotlin/demo/GammaSpec.kt"
        spec.write_text(spec.read_text(encoding="utf-8") + "// BR-BIZ-904 를 지키는 시험(대체됨)\n// BR-BIZ-9040 아님\n",
                        encoding="utf-8")
        code, _, report = self.run_trace()
        self.assertEqual(code, 0)
        items = report["checks"]["supersededInTests"]["items"]
        self.assertEqual([(i["id"], i["file"], i["line"]) for i in items],
                         [("BR-BIZ-904", "apps/alpha-api/src/test/kotlin/demo/GammaSpec.kt", 4)])

    # --- exit code ---------------------------------------------------------------------------------------------

    def test_missing_ledger_file_exits_2(self):
        (self.root / "docs/rules/WH.md").unlink()
        code, out, _ = self.run_trace()
        self.assertEqual(code, 2, out)
        self.assertIn("WH.md", out)

    def test_ledger_without_rule_table_exits_2(self):
        (self.root / "docs/rules/ADM.md").write_text("# ADM\n\n본문만 있고 규칙 표가 없다.\n\n## 진단\n", encoding="utf-8")
        code, out, _ = self.run_trace()
        self.assertEqual(code, 2, out)
        self.assertIn("ADM.md", out)

    def test_rule_row_outside_standard_table_exits_2(self):
        path = self.root / "docs/rules/PAY.md"
        path.write_text(path.read_text(encoding="utf-8").replace("| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |",
                                                                 "| ID | 규칙 | 상태 |"), encoding="utf-8")
        code, out, _ = self.run_trace()
        self.assertEqual(code, 2, out)
        self.assertIn("PAY.md", out)

    def test_strict_domain_with_violation_exits_1(self):
        self.add_rows("BIZ", "| BR-BIZ-920 | 시험 없음. | 확정 | 사용자 | D-T-001 | X | 없음 |")
        code, out, report = self.run_trace("--strict-domains", "BIZ")
        self.assertEqual(code, 1, out)
        self.assertEqual(report["strictDomains"], ["BIZ"])
        self.assertEqual(report["strictViolations"], {"BIZ": 1})

    def test_strict_domain_without_violation_exits_0(self):
        self.add_rows("BIZ", "| BR-BIZ-920 | 시험 없음. | 확정 | 사용자 | D-T-001 | X | 없음 |")
        code, out, report = self.run_trace("--strict-domains", "PAY,WH")
        self.assertEqual(code, 0, out)
        self.assertEqual(report["strictViolations"], {})

    def test_default_mode_exits_0_with_violations_everywhere(self):
        self.add_rows("BIZ", "| BR-BIZ-920 | 시험 없음. | 확정 | 사용자 | D-T-404 | X | 없음 |",
                      "| BR-BIZ-921 | 열 부족 | 확정 |")
        code, out, report = self.run_trace()
        self.assertEqual(code, 0, out)
        self.assertEqual(report["strictDomains"], [])
        checks = report["checks"]
        self.assertIn(("BR-BIZ-920", "NONE"), [(i["id"], i["reason"]) for i in checks["confirmedWithoutTest"]["items"]])
        self.assertIn(("BR-BIZ-921", "COLUMNS"), [(i["id"], i["kind"]) for i in checks["format"]["items"]])
        self.assertIn(("BR-BIZ-920", "D-T-404"), [(i["id"], i["ref"]) for i in checks["evidenceMissing"]["items"]])

    def test_unwritable_json_path_exits_2(self):
        bad = self.tmp / "no-such-dir/out.json"
        proc = subprocess.run([sys.executable, "-B", str(SCRIPT), "--root", str(self.root), "--json", str(bad)],
                              capture_output=True, text=True, timeout=60)
        self.assertEqual(proc.returncode, 2, proc.stdout + proc.stderr)
        self.assertIn("cannot write --json", proc.stderr)
        self.assertFalse(bad.exists())

    def test_unknown_strict_domain_is_usage_error(self):
        code, out, _ = self.run_trace("--strict-domains", "BIZ,XYZ")
        self.assertEqual(code, 2, out)
        self.assertIn("unknown domain XYZ", out)


if __name__ == "__main__":
    unittest.main()
