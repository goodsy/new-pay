"""fingerprint.sh가 판정에 영향을 주는 변경을 놓치지 않는지 검사하는 harness 자체 시험 (HARNESS-001).

실제 저장소를 건드리지 않도록 임시 git 저장소에 스크립트를 복사해 실행한다.
"""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/fingerprint.sh"


class FingerprintGate(unittest.TestCase):

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.repo)
        self.git("init", "-q")
        (self.repo / ".gitignore").write_text("*.log\nbuild/\n__pycache__/\n", encoding="utf-8")
        script = self.repo / "harness/scripts/fingerprint.sh"
        script.parent.mkdir(parents=True)
        shutil.copy(SCRIPT, script)
        script.chmod(0o755)
        self.source = self.repo / "apps/demo/src/main/A.kt"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("class A\n", encoding="utf-8")
        (self.repo / "harness/commands.json").write_text('{"minTests": 6}\n', encoding="utf-8")
        (self.repo / "harness/tasks.json").write_text('{"status": "RUNNING"}\n', encoding="utf-8")
        self.tracked = self.repo / "README.md"
        self.tracked.write_text("readme\n", encoding="utf-8")
        self.git("add", "README.md", ".gitignore")
        self.git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "init")

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True)

    def fingerprint(self):
        result = subprocess.run([str(self.repo / "harness/scripts/fingerprint.sh")],
                                cwd=self.repo, capture_output=True, text=True)
        value = next((line.split("=", 1)[1] for line in result.stdout.splitlines()
                      if line.startswith("codeFingerprint=")), None)
        return result.returncode, value

    def test_stable_when_nothing_changes(self):
        self.assertEqual(self.fingerprint(), self.fingerprint())

    def test_untracked_source_change_is_detected(self):
        _, before = self.fingerprint()
        self.source.write_text("class A { val x = 1 }\n", encoding="utf-8")
        self.assertNotEqual(before, self.fingerprint()[1])

    def test_gate_definition_change_is_detected(self):
        _, before = self.fingerprint()
        (self.repo / "harness/commands.json").write_text('{"minTests": 0}\n', encoding="utf-8")
        self.assertNotEqual(before, self.fingerprint()[1])

    def test_mode_change_is_detected(self):
        _, before = self.fingerprint()
        os.chmod(self.source, 0o755)
        self.assertNotEqual(before, self.fingerprint()[1])

    def test_permission_change_without_exec_bit_is_ignored(self):
        _, before = self.fingerprint()
        os.chmod(self.source, 0o664)  # clone별 umask 차이
        self.assertEqual(before, self.fingerprint()[1])

    def test_deleted_tracked_file_fails(self):
        self.tracked.unlink()
        self.assertEqual(self.fingerprint()[0], 1)

    def test_unreadable_file_fails(self):
        os.chmod(self.source, 0o000)
        self.addCleanup(os.chmod, self.source, 0o644)
        self.assertEqual(self.fingerprint()[0], 1)

    def test_repository_without_head_fails(self):
        shutil.rmtree(self.repo / ".git")
        self.git("init", "-q")
        self.assertEqual(self.fingerprint()[0], 1)

    def test_progress_state_change_is_excluded(self):
        _, before = self.fingerprint()
        (self.repo / "harness/tasks.json").write_text('{"status": "DONE"}\n', encoding="utf-8")
        self.assertEqual(before, self.fingerprint()[1])

    def test_ignored_file_under_source_fails(self):
        (self.source.parent / "fixture.log").write_text("x\n", encoding="utf-8")
        code, _ = self.fingerprint()
        self.assertEqual(code, 1)

    def test_ignored_file_under_shared_library_or_build_logic_fails(self):
        for directory in ("libs/support/src/test", "build-logic/src/main"):
            target = self.repo / directory / "fixture.log"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("x\n", encoding="utf-8")
            self.assertEqual(self.fingerprint()[0], 1, directory)
            target.unlink()

    def test_build_outputs_are_not_flagged(self):
        # build/는 무시 대상이며 소스가 아니다(kotlin-dsl이 build-logic/build 아래에 .kts를 만든다).
        for path in ("build-logic/build/kotlin-dsl/extracted/a.gradle.kts", "apps/demo/build/src/x.log"):
            target = self.repo / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("x\n", encoding="utf-8")
        self.assertEqual(self.fingerprint()[0], 0)

    def test_ignored_frontend_source_fails(self):
        # ADMIN-BOOT-001 m-8: 프론트는 apps/*/src 밖(apps/*/frontend)에 있으므로 기존 가드가 보지 않는다.
        # 무시 패턴에 걸린 프론트 소스·설정(빌드 입력)은 fingerprint에서 빠지므로 거부해야 한다.
        frontend = self.repo / "apps/demo/frontend"
        ignored = ["src/main.local.tsx", "settings.local.json", "vite.local.ts", "index.html", ".npmrc",
                   "public/robots.local.txt"]
        with (self.repo / ".gitignore").open("a", encoding="utf-8") as fh:
            fh.write("".join(f"apps/demo/frontend/{name}\n" for name in ignored))
        for name in ignored:
            target = frontend / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("x\n", encoding="utf-8")
            code, _ = self.fingerprint()
            self.assertEqual(code, 1, name)
            target.unlink()

    def test_frontend_node_modules_and_build_outputs_are_not_flagged(self):
        # 의존성 설치물·빌드 출력은 빌드 입력이 아니다(lockfile·소스가 입력). 하위 패키지의 src·json·ts에 걸리면 안 된다.
        with (self.repo / ".gitignore").open("a", encoding="utf-8") as fh:
            fh.write("node_modules/\n")
        for path in ("apps/demo/frontend/node_modules/pkg/src/index.ts", "apps/demo/frontend/node_modules/pkg/package.json",
                     "apps/demo/frontend/node_modules/.package-lock.json", "apps/demo/frontend/build/dist/index.html",
                     "apps/demo/frontend/node_modules/pkg/public/logo.svg"):
            target = self.repo / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("x\n", encoding="utf-8")
        self.assertEqual(self.fingerprint()[0], 0)

    def test_tracked_frontend_source_change_is_detected(self):
        source = self.repo / "apps/demo/frontend/src/App.tsx"
        source.parent.mkdir(parents=True)
        source.write_text("export const A = 1\n", encoding="utf-8")
        _, before = self.fingerprint()
        source.write_text("export const A = 2\n", encoding="utf-8")
        self.assertNotEqual(before, self.fingerprint()[1])

    def test_pycache_under_harness_is_allowed(self):
        cache = self.repo / "harness/scripts/__pycache__/m.pyc"
        cache.parent.mkdir()
        cache.write_bytes(b"\x00")
        code, _ = self.fingerprint()
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
