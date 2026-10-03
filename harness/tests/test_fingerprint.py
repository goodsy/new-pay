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

    def test_pycache_under_harness_is_allowed(self):
        cache = self.repo / "harness/scripts/__pycache__/m.pyc"
        cache.parent.mkdir()
        cache.write_bytes(b"\x00")
        code, _ = self.fingerprint()
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
