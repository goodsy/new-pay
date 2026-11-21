#!/usr/bin/env python3
"""tasks.json 기록 규칙 검사 (PROC-RECORD-001, 결정 D-PROC-002, HARNESS-GUIDE §12).

사용: check-records.py <tasks.json 경로>
exit 0 = 규칙 충족, 1 = 위반(목록 출력), 2 = 사용법/파일 오류.
진행 기록은 fingerprint 밖(검증 뒤에 바뀜)이라 VERIFY-GATE 단계에 넣지 않는다. Coordinator가 기록을 바꿀 때마다 실행한다.
"""
import json
import sys
from pathlib import Path

MAX_TASK_LINES = 100
MAX_FILE_BYTES = 100 * 1024
STATUSES = {"BACKLOG", "READY", "RUNNING", "VERIFYING", "REVIEW", "DONE", "IN_PROGRESS",
            "WAITING_DECISION", "ENVIRONMENT_BLOCKED", "FAILED", "PARTIAL"}


def problems_of(path: Path) -> list[str]:
    raw = path.read_bytes()
    doc = json.loads(raw.decode("utf-8"))
    problems = []
    if len(raw) > MAX_FILE_BYTES:
        problems.append(f"file {len(raw)} bytes > {MAX_FILE_BYTES}")
    if doc.get("schemaVersion") != 2:
        problems.append(f"schemaVersion {doc.get('schemaVersion')} != 2")
    keys = doc.get("taskKeys") or []
    if not keys:
        problems.append("taskKeys missing")
    for entry in doc.get("archive") or []:
        archived = path.parent.parent / entry.split(" ", 1)[0]
        if not archived.is_file():
            problems.append(f"archive file missing: {archived}")
    seen = set()
    for task in doc.get("tasks") or []:
        tid = task.get("id", "?")
        if tid in seen:
            problems.append(f"{tid}: duplicate id")
        seen.add(tid)
        if list(task) != keys:
            missing = [k for k in keys if k not in task]
            extra = [k for k in task if k not in keys]
            problems.append(f"{tid}: keys differ from taskKeys (missing {missing}, extra {extra}, or order)")
        if task.get("status") not in STATUSES:
            problems.append(f"{tid}: unknown status {task.get('status')}")
        if not isinstance(task.get("nextResumeAction"), str) or not task["nextResumeAction"].strip():
            problems.append(f"{tid}: nextResumeAction empty")
        if not isinstance(task.get("openItems"), list):
            problems.append(f"{tid}: openItems must be a list")
        gate = task.get("lastGate")
        if task.get("status") == "DONE" and isinstance(gate, dict) and gate.get("verdict") not in (None, "PASS"):
            problems.append(f"{tid}: DONE but lastGate verdict {gate.get('verdict')}")
        lines = len(json.dumps(task, ensure_ascii=False, indent=2).splitlines())
        if lines > MAX_TASK_LINES:
            problems.append(f"{tid}: {lines} lines > {MAX_TASK_LINES}")
    if not seen:
        problems.append("no tasks")
    return problems


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check-records.py <tasks.json>", file=sys.stderr)
        return 2
    try:
        problems = problems_of(Path(sys.argv[1]))
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    for problem in problems:
        print(f"problem: {problem}")
    print(f"records={'OK' if not problems else 'FAIL'}")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
