"""영향 범위 게이트의 범위 계산 시험 (PROC-GATE-001, 결정 D-PROC-001 — R-1).

범위를 좁게 잡아 시험을 빠뜨리는 것이 가장 큰 위험이다. 그래서 이 시험은
1) 실제 commands.json 규칙으로 대표 경로가 올바른 단위(모듈 사이 입력 포함)로 가는지,
2) 분류할 수 없는 경로가 FULL(전체 게이트 필요)로 가는지,
3) 실제 git 저장소에서 변경 경로·새 패키지 디렉터리·rename을 빠짐없이 세는지,
4) settings.gradle.kts의 모든 앱·프론트가 단위로 등록돼 있는지
를 검사한다.
"""
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("gate_scope_under_test", ROOT / "harness/scripts/gate_scope.py")
gate_scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate_scope)

REGISTRY = json.loads((ROOT / "harness/commands.json").read_text(encoding="utf-8"))
SECTION = REGISTRY["scopedGate"]
RULES = SECTION["pathRules"]
SETTINGS = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
APPS = [m for line in SETTINGS.splitlines() if not line.strip().startswith("//")
        for m in re.findall(r'include\(":apps:([a-z0-9-]+)"\)', line)]


def units_of(path: str) -> tuple[str, list[str]]:
    kind, rule = gate_scope.classify(path, RULES)
    return kind, (rule or {}).get("units", [])


class Classification(unittest.TestCase):

    def test_registry_section_is_valid(self):
        self.assertEqual(gate_scope.validate(SECTION), [])

    def test_documents_and_records_select_no_unit(self):
        for path in ("docs/DECISIONS.md", "docs/assessment/CURRENT-STATE.md", "README.md", "CLAUDE.md",
                     ".claude/agents/paycore-reviewer.md", "harness/tasks.json", "harness/questions.json",
                     "harness/archive/tasks-2026-11-20.json"):
            kind, units = units_of(path)
            self.assertIn(kind, gate_scope.NOT_BUILD_INPUT, path)
            self.assertEqual(units, [], path)

    def test_documents_read_by_tests_select_their_unit(self):
        # 화면 시안 kit은 docs 아래지만 UiRefKit 시험이 읽는다. DOC 규칙보다 먼저 잡혀야 한다.
        self.assertEqual(units_of("docs/pg-workspace-react-kit/styles.css"), ("MODULE", ["ADMIN-FRONTEND"]))

    def test_frontend_change_also_runs_the_kotlin_tests_that_serve_its_bundle(self):
        self.assertEqual(units_of("apps/internal-admin/frontend/src/App.tsx"),
                         ("MODULE", ["ADMIN-FRONTEND", "INTERNAL-ADMIN"]))
        self.assertEqual(units_of("apps/payment-api/frontend/src/main.tsx"),
                         ("MODULE", ["PAYMENT-FRONTEND", "PAYMENT-API"]))

    def test_contract_golden_files_run_both_reader_modules(self):
        self.assertEqual(units_of("apps/master-api/src/test/resources/contracts/merchant-ops-response-shapes-v1.golden.json"),
                         ("MODULE", ["MASTER-API", "INTERNAL-ADMIN"]))
        self.assertEqual(units_of("apps/payment-api/src/test/resources/contracts/admin-payment-list-v1.golden.json"),
                         ("MODULE", ["PAYMENT-API", "INTERNAL-ADMIN"]))

    def test_simulator_main_sources_also_run_payment_scan(self):
        self.assertEqual(units_of("apps/institution-simulator/src/main/kotlin/com/paycore/simulator/X.kt")[1],
                         ["INSTITUTION-SIMULATOR", "PAYMENT-API"])
        self.assertEqual(units_of("apps/institution-simulator/src/main/resources/templates/a.html")[1],
                         ["INSTITUTION-SIMULATOR", "PAYMENT-API"])
        self.assertEqual(units_of("apps/institution-simulator/src/test/kotlin/X.kt")[1], ["INSTITUTION-SIMULATOR"])

    def test_application_yaml_of_any_app_also_runs_master_legacy_scan(self):
        for app in APPS:
            _, units = units_of(f"apps/{app}/src/main/resources/application.yml")
            self.assertIn("MASTER-API", units, app)
            # NoLegacyImsDependencySpec는 resources 아래 하위 디렉터리(config/ 등)의 application*.yml도 본다(리뷰 m-1).
            _, units = units_of(f"apps/{app}/src/main/resources/config/application-x.yml")
            self.assertIn("MASTER-API", units, app)

    def test_download_marker_under_sources_is_not_local(self):
        # 소스 아래 Zone.Identifier는 디렉터리 목록을 비교하는 시험에 영향을 줄 수 있다(리뷰 m-2) — 모듈 규칙으로 간다.
        kind, units = units_of("apps/internal-admin/src/main/resources/db/migration/admin/V9__x.sql:Zone.Identifier")
        self.assertEqual((kind, units), ("MODULE", ["INTERNAL-ADMIN"]))

    def test_migrations_and_tests_stay_in_their_module(self):
        self.assertEqual(units_of("apps/master-api/src/main/resources/db/migration/master/V19__x.sql"), ("MODULE", ["MASTER-API"]))
        self.assertEqual(units_of("apps/payment-api/src/test/kotlin/com/paycore/payment/XSpec.kt"), ("MODULE", ["PAYMENT-API"]))

    def test_shared_and_build_inputs_require_full_gate(self):
        for path in ("libs/test-support/src/main/kotlin/X.kt", "build-logic/src/main/kotlin/paycore.frontend.gradle.kts",
                     "gradle/libs.versions.toml", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                     "apps/master-api/build.gradle.kts", "harness/scripts/verify-gate.py", "harness/commands.json",
                     "harness/e2e/merchant_code_e2e.py", "docker/mysql/init.sql", "compose.yaml", ".gitignore",
                     "apps/new-app/src/main/kotlin/X.kt", "unknown-root-file.txt"):
            self.assertEqual(gate_scope.classify(path, RULES)[0], gate_scope.FULL, path)

    def test_local_files_never_read_by_the_build_select_no_unit(self):
        for path in ("pg-workspace-react-kit.zip", "GUIDE.md:Zone.Identifier", "apps/internal-admin/frontend/tsconfig.tsbuildinfo"):
            self.assertEqual(units_of(path), ("LOCAL", []), path)

    def test_harness_tests_need_no_build_unit_but_are_build_inputs(self):
        kind, units = units_of("harness/tests/test_gate_scope.py")
        self.assertEqual((kind, units), ("HARNESS-TEST", []))
        self.assertNotIn(kind, gate_scope.NOT_BUILD_INPUT)

    def test_validate_rejects_unknown_unit_kind_and_unit_on_doc_rule(self):
        broken = json.loads(json.dumps(SECTION))
        broken["pathRules"] = [{"pattern": "^a/", "kind": "MODULE", "units": ["NOPE"]},
                               {"pattern": "^b/", "kind": "WHATEVER"},
                               {"pattern": "^c/", "kind": "DOC", "units": ["MASTER-API"]},
                               {"pattern": "^d/", "kind": "MODULE"}]
        problems = gate_scope.validate(broken)
        self.assertEqual(len(problems), 4, problems)


class Registry(unittest.TestCase):

    def test_every_app_has_a_kotlin_unit_running_its_whole_test_task(self):
        self.assertGreaterEqual(len(APPS), 1)
        kotlin_units = {u: d for u, d in SECTION["units"].items() if d["tasks"][-1].endswith(":test")}
        for app in APPS:
            match = [d for d in kotlin_units.values() if d["tasks"] == [f":apps:{app}:test"]]
            self.assertEqual(len(match), 1, app)
            self.assertEqual(match[0]["reports"], [f"apps/{app}/build/test-results/test"], app)
            self.assertTrue(match[0]["usesContainers"], app)

    def test_every_app_source_path_selects_that_apps_unit(self):
        for app in APPS:
            unit = next(u for u, d in SECTION["units"].items() if d["tasks"] == [f":apps:{app}:test"])
            for sub in ("src/main/kotlin/com/paycore/X.kt", "src/test/kotlin/com/paycore/XSpec.kt",
                        "src/main/resources/db/migration/V1__x.sql", "src/test/resources/x.json"):
                self.assertIn(unit, units_of(f"apps/{app}/{sub}")[1], f"{app}/{sub}")

    def test_every_frontend_has_a_unit_with_all_gate_tasks(self):
        frontends = [p.parent.parent.name for p in (ROOT / "apps").glob("*/frontend/package-lock.json")]
        self.assertGreaterEqual(len(frontends), 1)
        for app in frontends:
            expected = [f":apps:{app}:{t}" for t in ("frontendNodeVerify", "frontendInstall", "frontendLockfileCheck",
                                                      "frontendTypecheck", "frontendTest")]
            match = [d for d in SECTION["units"].values() if d["tasks"] == expected]
            self.assertEqual(len(match), 1, app)
            self.assertEqual(match[0]["reports"], [f"apps/{app}/build/test-results/frontend"], app)
            self.assertIn(next(u for u, d in SECTION["units"].items() if d["tasks"] == expected),
                          units_of(f"apps/{app}/frontend/src/x.ts")[1])

    def test_unit_minimums_are_positive_and_reports_do_not_overlap(self):
        seen = set()
        for unit, d in SECTION["units"].items():
            self.assertGreaterEqual(d["minTests"], 1, unit)
            for report in d["reports"]:
                self.assertNotIn(report, seen, unit)
                seen.add(report)

    def test_scoped_gradle_run_uses_the_same_no_cache_rules_as_full_gate(self):
        full = next(c for c in REGISTRY["commands"] if c["id"] == "GRADLE-CHECK")
        self.assertEqual(SECTION["gradleArgv"], full["argv"][:-1])
        self.assertEqual(SECTION["outputMustNotMatch"], full["outputMustNotMatch"])
        self.assertLessEqual(SECTION["gradleTimeoutSeconds"], full["timeoutSeconds"])

    def test_scoped_gate_timeout_covers_its_worst_case(self):
        gate_spec = importlib.util.spec_from_file_location("verify_gate_for_scope", ROOT / "harness/scripts/verify-gate.py")
        verify_gate = importlib.util.module_from_spec(gate_spec)
        gate_spec.loader.exec_module(verify_gate)
        timeouts = {c["id"]: c["timeoutSeconds"] for c in REGISTRY["commands"]}
        stages = ([timeouts["FINGERPRINT"]] * 2 + [timeouts["HARNESS-SELFTEST"], SECTION["gradleTimeoutSeconds"],
                  timeouts["IMAGE-DIGESTS"]] + [SECTION["summaryTimeoutSeconds"]] * len(SECTION["units"]))
        worst = sum(stages) + len(stages) * 2 * verify_gate.POST_KILL_WAIT_SECONDS
        self.assertGreaterEqual(timeouts["VERIFY-GATE-SCOPED"], worst)


def git(root: Path, *args: str) -> str:
    return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True).stdout


class ChangedPaths(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        git(self.root, "init", "-q")
        git(self.root, "config", "user.email", "t@example.invalid")
        git(self.root, "config", "user.name", "t")
        for path in ("docs/a.md", "apps/payment-api/src/main/kotlin/com/paycore/pay/A.kt",
                     "apps/master-api/src/main/kotlin/com/paycore/m/B.kt", "libs/test-support/src/main/kotlin/C.kt"):
            self.write(path, "v1\n")
        git(self.root, "add", "-A")
        git(self.root, "commit", "-q", "-m", "base")

    def write(self, path: str, text: str):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def scope(self, base="HEAD"):
        return gate_scope.compute(self.root, base, SECTION)

    def test_documents_only_selects_nothing(self):
        self.write("docs/a.md", "v2\n")
        self.write("docs/new.md", "new\n")
        result = self.scope()
        self.assertEqual((result["units"], result["fullGateRequiredBy"]), ([], []))
        self.assertEqual([p["path"] for p in result["paths"]], ["docs/a.md", "docs/new.md"])
        self.assertEqual((result["classification"], result["buildInputChanged"]), ("DOCS_ONLY", False))

    def test_records_only_change_is_an_error(self):
        # 커밋 뒤 tasks.json만 고치고 base 없이 돌리면 커밋된 배치가 시험 없이 통과한다 — 막는다(재리뷰 m-A).
        self.write("harness/tasks.json", "{}\n")
        with self.assertRaises(gate_scope.ScopeError):
            self.scope()

    def test_module_change_is_classified_as_build(self):
        self.write("apps/payment-api/src/main/kotlin/com/paycore/pay/A.kt", "v2\n")
        self.write("harness/tasks.json", "{}\n")
        result = self.scope()
        self.assertEqual((result["classification"], result["buildInputChanged"]), ("BUILD", True))

    def test_change_in_existing_package_selects_only_its_module(self):
        self.write("apps/payment-api/src/main/kotlin/com/paycore/pay/A.kt", "v2\n")
        self.assertEqual(self.scope()["units"], ["PAYMENT-API"])

    def test_new_package_directory_also_runs_master_package_name_scan(self):
        self.write("apps/payment-api/src/main/kotlin/com/paycore/pay/newpkg/N.kt", "new\n")
        self.assertEqual(self.scope()["units"], ["MASTER-API", "PAYMENT-API"])

    def test_deleted_file_counts(self):
        (self.root / "apps/master-api/src/main/kotlin/com/paycore/m/B.kt").unlink()
        self.assertEqual(self.scope()["units"], ["MASTER-API"])

    def test_rename_across_modules_counts_old_and_new_paths(self):
        git(self.root, "mv", "apps/master-api/src/main/kotlin/com/paycore/m/B.kt",
            "apps/payment-api/src/main/kotlin/com/paycore/pay/B.kt")
        units = self.scope()["units"]
        self.assertIn("MASTER-API", units)
        self.assertIn("PAYMENT-API", units)

    def test_untracked_shared_library_file_requires_full_gate(self):
        self.write("libs/test-support/src/main/kotlin/D.kt", "new\n")
        result = self.scope()
        self.assertEqual(result["fullGateRequiredBy"], ["libs/test-support/src/main/kotlin/D.kt"])

    def test_committed_changes_are_counted_against_an_older_base(self):
        base = git(self.root, "rev-parse", "HEAD").strip()
        self.write("apps/payment-api/src/main/kotlin/com/paycore/pay/A.kt", "v2\n")
        git(self.root, "commit", "-q", "-am", "batch")
        # HEAD 기준 변경 0건은 성공이 아니라 오류다(커밋된 배치가 검사 없이 통과하는 일 차단 — 리뷰 M-1).
        with self.assertRaises(gate_scope.ScopeError):
            self.scope()
        self.assertEqual(self.scope(base)["units"], ["PAYMENT-API"])  # 이전 base 기준: 커밋된 변경 포함

    def test_base_on_another_branch_is_rejected(self):
        git(self.root, "checkout", "-q", "-b", "side")
        self.write("docs/a.md", "side\n")
        git(self.root, "commit", "-q", "-am", "side")
        side = git(self.root, "rev-parse", "HEAD").strip()
        git(self.root, "checkout", "-q", "-")
        self.write("docs/a.md", "main\n")
        with self.assertRaises(gate_scope.ScopeError):
            self.scope(side)

    def test_invalid_or_unknown_base_is_an_error(self):
        for base in ("main; rm -rf /", "HEAD~1", "deadbeefdeadbeef"):
            with self.assertRaises(gate_scope.ScopeError, msg=base):
                self.scope(base)


class BuildFingerprint(unittest.TestCase):

    @staticmethod
    def manifest(entries: dict) -> str:
        lines = [f"644 {hashlib.sha256(v.encode()).hexdigest()} {p}" for p, v in sorted(entries.items())]
        return "\n".join(lines + ["headRevision=abc", "codeFingerprint=sha256:" + "0" * 64]) + "\n"

    def test_document_change_keeps_build_fingerprint(self):
        base = {"docs/a.md": "1", "apps/master-api/src/main/kotlin/A.kt": "1", "harness/tests/test_x.py": "1"}
        changed = dict(base, **{"docs/a.md": "2", "README.md": "new", "pg-workspace-react-kit.zip": "z"})
        self.assertEqual(gate_scope.build_fingerprint(self.manifest(base), RULES),
                         gate_scope.build_fingerprint(self.manifest(changed), RULES))

    def test_source_or_harness_test_change_changes_build_fingerprint(self):
        base = {"docs/a.md": "1", "apps/master-api/src/main/kotlin/A.kt": "1", "harness/tests/test_x.py": "1"}
        for path in ("apps/master-api/src/main/kotlin/A.kt", "harness/tests/test_x.py"):
            changed = dict(base, **{path: "2"})
            self.assertNotEqual(gate_scope.build_fingerprint(self.manifest(base), RULES),
                                gate_scope.build_fingerprint(self.manifest(changed), RULES), path)

    def test_output_without_manifest_has_no_build_fingerprint(self):
        self.assertIsNone(gate_scope.build_fingerprint("headRevision=abc\ncodeFingerprint=sha256:" + "0" * 64, RULES))


if __name__ == "__main__":
    unittest.main()
