"""check-records.py 시험 (PROC-RECORD-001, D-PROC-002). 실제 tasks.json이 아니라 임시 fixture로 규칙만 검사한다
(진행 기록은 fingerprint 밖이라 게이트 결과가 그 내용에 기대면 안 된다)."""
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/check-records.py"
KEYS = ["id", "title", "status", "phase", "owner", "dependsOn", "spec", "allowedWritePaths", "maxRepairAttempts",
        "lastGate", "openItems", "nextResumeAction", "detail"]


def task(tid="T-1", **over):
    base = {"id": tid, "title": "t", "status": "DONE", "phase": None, "owner": None, "dependsOn": [], "spec": None,
            "allowedWritePaths": [], "maxRepairAttempts": 3, "lastGate": {"verdict": "PASS", "evidence": "e.json"},
            "openItems": [], "nextResumeAction": "없음 — 완료", "detail": None}
    base.update(over)
    return base


class CheckRecords(unittest.TestCase):

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        (self.root / "harness/archive").mkdir(parents=True)
        (self.root / "harness/archive/old.json").write_text("{}", encoding="utf-8")

    def check(self, tasks, **over):
        doc = {"schemaVersion": 2, "taskKeys": KEYS, "archive": ["harness/archive/old.json (원본)"], "tasks": tasks}
        doc.update(over)
        path = self.root / "harness/tasks.json"
        path.write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
        proc = subprocess.run([sys.executable, str(SCRIPT), str(path)], capture_output=True, text=True)
        return proc.returncode, proc.stdout

    def test_valid_records_pass(self):
        self.assertEqual(self.check([task(), task("T-2", status="BACKLOG", lastGate=None)])[0], 0)

    def test_extra_or_missing_key_fails(self):
        extra = task(); extra["gateRuns"] = []
        missing = task("T-2"); del missing["phase"]
        code, out = self.check([extra, missing])
        self.assertEqual(code, 1)
        self.assertIn("T-1: keys differ", out)
        self.assertIn("T-2: keys differ", out)

    def test_empty_resume_action_fails(self):
        self.assertEqual(self.check([task(nextResumeAction=" ")])[0], 1)

    def test_done_with_failed_last_gate_fails(self):
        code, out = self.check([task(lastGate={"verdict": "FAIL", "evidence": "x"})])
        self.assertEqual(code, 1)
        self.assertIn("DONE but lastGate verdict FAIL", out)

    def test_oversized_task_fails(self):
        self.assertEqual(self.check([task(openItems=[f"item {i}" for i in range(120)])])[0], 1)

    def test_duplicate_id_and_missing_archive_fail(self):
        code, out = self.check([task(), task()], archive=["harness/archive/missing.json (x)"])
        self.assertEqual(code, 1)
        self.assertIn("duplicate id", out)
        self.assertIn("archive file missing", out)

    def test_wrong_schema_version_fails(self):
        self.assertEqual(self.check([task()], schemaVersion=1)[0], 1)


if __name__ == "__main__":
    unittest.main()
