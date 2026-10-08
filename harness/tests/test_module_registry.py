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


APPS = [p for p in included_projects() if p.startswith(":apps:")]
LIBS = [p for p in included_projects() if p.startswith(":libs:")]


class ModuleRegistry(unittest.TestCase):

    def test_settings_includes_modules(self):
        # 파싱이 비어서 아래 검사가 모두 통과하는 일을 막는다.
        self.assertGreaterEqual(len(APPS), 1)
        self.assertGreaterEqual(len(LIBS), 1)

    def test_every_app_directory_is_included_in_settings(self):
        on_disk = sorted(f":apps:{d.parent.name}" for d in (ROOT / "apps").glob("*/build.gradle.kts"))
        self.assertEqual(on_disk, sorted(APPS))

    def test_gradle_check_expects_test_reports_of_every_module(self):
        reports = command("GRADLE-CHECK")["expectedReports"]
        for project in APPS + LIBS:
            self.assertIn(f"{directory(project)}/build/test-results/test/TEST-*.xml", reports, project)

    def test_gradle_check_requires_test_task_line_of_every_module(self):
        rules = command("GRADLE-CHECK")["outputMustMatch"]
        for project in APPS + LIBS:
            self.assertIn(f"^> Task {project}:test$", rules, project)

    def test_test_summary_reads_every_module(self):
        argv = command("TEST-SUMMARY")["argv"]
        for project in APPS + LIBS:
            self.assertIn(f"{directory(project)}/build/test-results/test", argv, project)

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

    def test_selftest_minimum_equals_number_of_harness_tests(self):
        # 시험을 추가하면 하한도 올린다(내리는 변경은 시험 삭제와 함께만 드러난다 — §7).
        count = sum(len(re.findall(r"^    def test_", p.read_text(encoding="utf-8"), re.MULTILINE))
                    for p in (ROOT / "harness/tests").glob("test_*.py"))
        self.assertEqual(command("HARNESS-SELFTEST")["minTests"], count)


if __name__ == "__main__":
    unittest.main()
