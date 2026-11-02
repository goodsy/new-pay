"""Gradle 모듈 목록과 검증 명령 등록이 어긋나지 않는지 검사 (HARNESS-VERIFICATION-CONTRACT §7, MASTER-BOOT-001).

새 실행 단위/시험 라이브러리를 settings.gradle.kts에 추가하고 commands.json·cve-scan.py 등록을 빠뜨리면, 그 모듈의 테스트가
GRADLE-CHECK 출력 규칙·TEST-SUMMARY 집계·SBOM/CVE 검사에서 조용히 빠진다. 이 시험은 실제 저장소 파일(settings.gradle.kts,
apps/*/build.gradle.kts, harness/commands.json, harness/scripts/cve-scan.py)을 읽어 그 누락을 실패로 만든다.
"""
import importlib.util
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMMANDS = json.loads((ROOT / "harness/commands.json").read_text(encoding="utf-8"))
SETTINGS = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")

spec = importlib.util.spec_from_file_location("cve_scan_registry", ROOT / "harness/scripts/cve-scan.py")
cve_scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cve_scan)


def command(command_id: str) -> dict:
    found = [c for c in COMMANDS["commands"] if c["id"] == command_id]
    if len(found) != 1:
        raise AssertionError(f"commands.json must define exactly one {command_id}, found {len(found)}")
    return found[0]


def included_projects() -> list[str]:
    """settings.gradle.kts의 include(":apps:x") / include(":libs:x") 경로(주석 줄 제외)."""
    paths = []
    for line in SETTINGS.splitlines():
        stripped = line.strip()
        if stripped.startswith("//"):
            continue
        paths += re.findall(r'include\("(:(?:apps|libs):[a-z0-9-]+)"\)', stripped)
    return paths


def directory(project_path: str) -> str:
    return project_path.lstrip(":").replace(":", "/")


gate_spec = importlib.util.spec_from_file_location("verify_gate_registry", ROOT / "harness/scripts/verify-gate.py")
verify_gate = importlib.util.module_from_spec(gate_spec)
gate_spec.loader.exec_module(verify_gate)


def gate_timeout_problems(registry: dict) -> list[str]:
    """VERIFY-GATE 바깥 timeout이 안쪽 단계 최악 시간보다 짧으면 문제(CP-QUERY-001 F-3).

    안쪽 최악 = GATE 순서의 각 단계 timeoutSeconds 합 + 단계마다 SIGKILL 뒤 대기 두 번(communicate·wait, POST_KILL_WAIT_SECONDS).
    바깥이 더 짧으면 GRADLE-CHECK가 자기 timeout 전에 잘려 판정(FAIL/환경)이 흐려진다.
    """
    timeouts = {c["id"]: c["timeoutSeconds"] for c in registry["commands"]}
    inner = sum(timeouts[stage] + 2 * verify_gate.POST_KILL_WAIT_SECONDS for stage in verify_gate.GATE)
    outer = timeouts["VERIFY-GATE"]
    return [] if outer >= inner else [f"VERIFY-GATE timeoutSeconds {outer} < worst case of its stages {inner}"]


APPS = [p for p in included_projects() if p.startswith(":apps:")]
LIBS = [p for p in included_projects() if p.startswith(":libs:")]


def included_builds_with_tests() -> list[str]:
    """settings.gradle.kts의 includeBuild("x") 중 src/test가 있는 것(ADMIN-UI-001 리뷰 M-1 — build-logic 시험이 루트 check에서 빠지지 않게)."""
    builds = []
    for line in SETTINGS.splitlines():
        stripped = line.strip()
        if stripped.startswith("//"):
            continue
        builds += [b for b in re.findall(r'includeBuild\("([^"]+)"\)', stripped) if (ROOT / b / "src/test").is_dir()]
    return builds


BUILDS = included_builds_with_tests()
ROOT_BUILD = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")


class ModuleRegistry(unittest.TestCase):

    def test_settings_includes_modules(self):
        # 파싱이 비어서 아래 검사가 모두 통과하는 일을 막는다.
        self.assertGreaterEqual(len(APPS), 1)
        self.assertGreaterEqual(len(LIBS), 1)

    def test_every_app_directory_is_included_in_settings(self):
        on_disk = sorted(f":apps:{d.parent.name}" for d in (ROOT / "apps").glob("*/build.gradle.kts"))
        self.assertEqual(on_disk, sorted(APPS))

    def test_gradle_check_expects_test_reports_of_every_module(self):
        check = command("GRADLE-CHECK")
        reports = check["expectedReports"]
        for project in APPS + LIBS:
            self.assertIn(f"{directory(project)}/build/test-results/test/TEST-*.xml", reports, project)
        # 시험이 있는 included build(build-logic)도 같은 규칙: 보고서 기대·보관.
        for build in BUILDS:
            self.assertIn(f"{build}/build/test-results/test/TEST-*.xml", reports, build)
            self.assertIn(f"{build}/build/test-results/test/TEST-*.xml", check["archiveGlobs"], build)

    def test_gradle_check_requires_test_task_line_of_every_module(self):
        rules = command("GRADLE-CHECK")["outputMustMatch"]
        for project in APPS + LIBS:
            self.assertIn(f"^> Task {project}:test$", rules, project)
        # included build 시험은 루트 `check`가 직접 의존해야 실행된다(루트 check는 included build task를 자동으로 고르지 않는다).
        self.assertTrue(BUILDS, "settings.gradle.kts includeBuild parsing found no build with src/test")
        for build in BUILDS:
            self.assertIn(f"^> Task :{build}:test$", rules, build)
            self.assertIn(f'gradle.includedBuild("{build}").task(":test")', ROOT_BUILD, build)

    def test_test_summary_reads_every_module(self):
        argv = command("TEST-SUMMARY")["argv"]
        for project in APPS + LIBS:
            self.assertIn(f"{directory(project)}/build/test-results/test", argv, project)
        for build in BUILDS:
            self.assertIn(f"{build}/build/test-results/test", argv, build)

    def test_test_summary_minimum_matches_gradle_check_min_tests(self):
        argv = command("TEST-SUMMARY")["argv"]
        minimum = int(argv[argv.index("NOT_BEFORE_UTC") + 1])
        self.assertEqual(minimum, command("GRADLE-CHECK")["minTests"])

    def test_every_app_has_a_dependency_tree_command(self):
        for project in APPS:
            name = project.split(":")[-1]
            deps = command(f"GRADLE-DEPS-{name.upper()}")
            self.assertEqual(deps["argv"], ["./gradlew", "--no-daemon", "-q", f"{project}:dependencies",
                                            "--configuration", "runtimeClasspath"])

    def test_cve_scan_covers_every_app(self):
        self.assertEqual(sorted(cve_scan.MODULES), sorted(p.split(":")[-1] for p in APPS))

    def test_cve_scan_covers_every_frontend_lockfile(self):
        # ADMIN-BOOT-001 m-10: package-lock.json이 있는 apps/*/frontend는 모두 npm CVE 검사 대상이어야 한다.
        on_disk = sorted(str(p.parent.relative_to(ROOT)) for p in (ROOT / "apps").glob("*/frontend/package-lock.json"))
        self.assertEqual(sorted(cve_scan.FRONTENDS), on_disk)

    def test_frontend_test_reports_are_registered(self):
        # 프론트 Vitest 보고서가 GRADLE-CHECK 출력 규칙·보관·TEST-SUMMARY-FRONTEND 집계에서 빠지지 않게 한다(m-9, 리뷰 M2(a)).
        frontends = [p.parent.parent.name for p in (ROOT / "apps").glob("*/frontend/package-lock.json")]
        self.assertGreaterEqual(len(frontends), 1)
        check = command("GRADLE-CHECK")
        frontend_argv = command("TEST-SUMMARY-FRONTEND")["argv"]
        for app in frontends:
            for task in ("frontendNodeVerify", "frontendInstall", "frontendLockfileCheck", "frontendTypecheck", "frontendTest"):
                self.assertIn(f"^> Task :apps:{app}:{task}$", check["outputMustMatch"], f"{app}:{task}")
            self.assertIn(f"apps/{app}/build/test-results/frontend/TEST-*.xml", check["expectedReports"], app)
            self.assertIn(f"apps/{app}/build/test-results/frontend", frontend_argv, app)
        self.assertIn("apps/*/build/test-results/frontend/TEST-*.xml", check["archiveGlobs"])

    def test_frontend_summary_is_a_gate_stage_with_its_own_minimum(self):
        # 리뷰 M2(a): Vitest 보고서는 별도 하한으로 판정한다(합계에 묻히면 프론트 시험이 줄어도 드러나지 않는다).
        frontend = command("TEST-SUMMARY-FRONTEND")
        argv = frontend["argv"]
        self.assertEqual(argv[:4], ["python3", "harness/scripts/test-summary.py", "--not-before", "NOT_BEFORE_UTC"])
        minimum = int(argv[4])
        self.assertGreaterEqual(minimum, 1)
        self.assertEqual(minimum, frontend["minTests"])
        self.assertTrue(argv[5:])
        self.assertTrue(all(d.endswith("/build/test-results/frontend") for d in argv[5:]), argv[5:])
        gate = verify_gate.GATE
        self.assertEqual(gate.index("TEST-SUMMARY-FRONTEND"), gate.index("TEST-SUMMARY") + 1)

    def test_no_report_directory_is_counted_by_two_summaries(self):
        # 이중 계산 방지: 같은 보고서 디렉터리가 두 하한에 동시에 들어가면 한쪽 시험이 줄어도 다른 쪽 하한이 가린다.
        backend = set(command("TEST-SUMMARY")["argv"][5:])
        frontend = set(command("TEST-SUMMARY-FRONTEND")["argv"][5:])
        self.assertEqual(backend & frontend, set())
        self.assertFalse([d for d in backend if d.endswith("/frontend")], backend)

    def test_selftest_minimum_equals_number_of_harness_tests(self):
        # 시험을 추가하면 하한도 올린다(내리는 변경은 시험 삭제와 함께만 드러난다 — §7).
        count = sum(len(re.findall(r"^    def test_", p.read_text(encoding="utf-8"), re.MULTILINE))
                    for p in (ROOT / "harness/tests").glob("test_*.py"))
        self.assertEqual(command("HARNESS-SELFTEST")["minTests"], count)

    def test_verify_gate_timeout_covers_its_stages(self):
        # 대조(CP-QUERY-001 F-3): 이전 값 1800초는 GRADLE-CHECK 2400초보다 짧아 문제로 잡혀야 한다.
        shortened = json.loads(json.dumps(COMMANDS))
        next(c for c in shortened["commands"] if c["id"] == "VERIFY-GATE")["timeoutSeconds"] = 1800
        self.assertTrue(gate_timeout_problems(shortened))
        # 단계 목록이 비어 검사가 헛도는 일을 막는다.
        self.assertIn("GRADLE-CHECK", verify_gate.GATE)
        self.assertEqual(gate_timeout_problems(COMMANDS), [])

    def test_verify_gate_timeout_boundary(self):
        # 검토 #1 n-8 경계 대조: 바깥 = 안쪽 최악 합이면 통과, 하나 모자라면(합 − 1) 문제. 합은 시험이 독립 계산한다(단계 timeout 합 + 단계마다 kill 뒤 대기 2회).
        timeouts = {c["id"]: c["timeoutSeconds"] for c in COMMANDS["commands"]}
        inner = sum(timeouts[s] for s in verify_gate.GATE) + len(verify_gate.GATE) * 2 * verify_gate.POST_KILL_WAIT_SECONDS
        for outer, flagged in ((inner, False), (inner - 1, True)):
            registry = json.loads(json.dumps(COMMANDS))
            next(c for c in registry["commands"] if c["id"] == "VERIFY-GATE")["timeoutSeconds"] = outer
            self.assertEqual(bool(gate_timeout_problems(registry)), flagged, outer)


if __name__ == "__main__":
    unittest.main()
