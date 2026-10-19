"""가상 인증기관 Simulator와 PayCore 실행 단위 사이 Gradle 의존 격리(AUTHN-001 §13.5 n-5, D-AUTHN-004).

Simulator(apps/institution-simulator)는 외부 기관 대역이다. PayCore 앱 코드(도메인·DB 접근)를 classpath로 공유하면 "별도 프로세스·저장소"가 흐려지고
REAL/Simulator 격리 시험이 의미를 잃는다. 두 쪽 계약은 harness golden 파일로만 공유한다. 이 시험은 실제 build 파일을 읽어
(1) Simulator build가 `project(":apps:…")`를 참조하지 않고 (2) 어떤 PayCore 앱 build도 `:apps:institution-simulator`를 참조하지 않음을 확인한다.
검사 함수가 실제로 위반을 잡는지는 합성 build 문자열(위반 fixture)로 함께 본다. 주석 줄은 무시한다.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SIMULATOR = "institution-simulator"


def code_lines(text: str) -> list[str]:
    return [line for line in text.splitlines() if not line.strip().startswith("//")]


def app_project_references(text: str) -> list[str]:
    """build 스크립트 본문의 앱 project 참조(주석 줄 제외): `project(":apps:<name>")`와 type-safe accessor `projects.apps.<name>`(검토 n-7)."""
    found = []
    for line in code_lines(text):
        found += re.findall(r'project\(\s*"(:apps:[a-z0-9-]+)"\s*\)', line)
        found += [f"projects.apps.{name}" for name in re.findall(r'\bprojects\.apps\.([A-Za-z0-9]+)', line)]
    return found


def mentions_simulator(text: str) -> bool:
    return any(f":apps:{SIMULATOR}" in line or "projects.apps.institutionSimulator" in line for line in code_lines(text))


def paycore_app_imports(text: str) -> list[str]:
    """Kotlin 소스의 PayCore 앱 패키지 import(주석 줄 제외)."""
    return [m for line in code_lines(text) for m in re.findall(r'^\s*import\s+(com\.paycore\.(?:payment|master|financial|admin|batch)\b[\w.]*)', line)]


class SimulatorIsolation(unittest.TestCase):

    def test_simulator_build_has_no_paycore_app_project_dependency(self):
        build = (ROOT / "apps" / SIMULATOR / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertEqual(app_project_references(build), [])

    def test_no_paycore_app_build_depends_on_the_simulator(self):
        builds = [p for p in (ROOT / "apps").glob("*/build.gradle.kts") if p.parent.name != SIMULATOR]
        self.assertGreaterEqual(len(builds), 4)
        for path in builds:
            self.assertFalse(mentions_simulator(path.read_text(encoding="utf-8")), path)

    def test_simulator_sources_including_the_van_module_import_no_paycore_app_package(self):
        """AUTH-001 §11: 가상 VAN 모듈(van)도 PayCore 앱 패키지를 import하지 않는다(계약은 golden 파일로만 공유 — 패키지 의존은 ArchUnit도 본다)."""
        src = ROOT / "apps" / SIMULATOR / "src" / "main" / "kotlin"
        files = list(src.rglob("*.kt"))
        self.assertTrue(any("/van/" in p.as_posix() for p in files), "van module sources exist")
        for path in files:
            self.assertEqual(paycore_app_imports(path.read_text(encoding="utf-8")), [], path)

    def test_the_checks_catch_violations_and_ignore_comments(self):
        violating = 'dependencies {\n    implementation(project(":apps:payment-api"))\n}\n'
        commented = 'dependencies {\n    // implementation(project(":apps:payment-api"))\n}\n'
        self.assertEqual(app_project_references(violating), [":apps:payment-api"])
        self.assertEqual(app_project_references(commented), [])
        self.assertTrue(mentions_simulator('testImplementation(project(":apps:institution-simulator"))'))
        self.assertFalse(mentions_simulator('// project(":apps:institution-simulator")'))
        self.assertEqual(app_project_references("    implementation(projects.apps.paymentApi)"), ["projects.apps.paymentApi"])
        self.assertTrue(mentions_simulator("    testImplementation(projects.apps.institutionSimulator)"))
        self.assertEqual(paycore_app_imports("import com.paycore.payment.payment.domain.Payment\n// import com.paycore.master.X"),
                         ["com.paycore.payment.payment.domain.Payment"])
        self.assertEqual(paycore_app_imports("import com.paycore.simulator.van.domain.VanPlan"), [])


if __name__ == "__main__":
    unittest.main()
