#!/usr/bin/env python3
"""JUnit XML 보고서 요약 (HARNESS-GUIDE §7).

사용: test-summary.py --not-before <UTC ISO-8601> <min_tests> <report_dir> [<report_dir> ...]
보고서 없음/테스트 수 미달/실패·오류·skip 존재 시 exit 1. 0건은 성공이 아니다.
보고서 실행 timestamp가 --not-before 이전이면(build cache 복원 등 오래된 결과) exit 1.
--not-before 값은 WSL 안에서 `date -u +%Y-%m-%dT%H:%M:%SZ`로 GRADLE-CHECK 직전에 얻는다(호스트/WSL 시계 차이 배제).
Gradle JUnit XML timestamp는 offset 없는 UTC다(2026-10-03 Verifier 실행에서 실행 구간 11:49:33Z–11:50:59Z 안의 값으로 확인).
"""
import glob
import hashlib
import os
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone


def parse_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    # JUnit XML timestamp는 offset 없는 UTC로 기록된다.
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def main() -> int:
    args = sys.argv[1:]
    # 신선도 검사는 필수다. 없으면 cache 복원/이전 실행 보고서가 통과할 수 있다.
    if args[:1] != ["--not-before"] or len(args) < 2:
        print("--not-before <UTC ISO-8601> is required", file=sys.stderr)
        return 2
    not_before = parse_utc(args[1])
    args = args[2:]
    if len(args) < 2:
        print("usage: test-summary.py --not-before <UTC ISO-8601> <min_tests> <report_dir>...", file=sys.stderr)
        return 2
    min_tests = int(args[0])
    files = sorted(f for d in args[1:] for f in glob.glob(os.path.join(d, "TEST-*.xml")))
    if not files:
        print("FAIL: no JUnit reports found")
        return 1

    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    stale = []
    for path in files:
        suite = ET.parse(path).getroot()
        ts = suite.get("timestamp")
        if ts is None or parse_utc(ts) < not_before:
            stale.append(path)
        counts = {k: int(suite.get(k, 0)) for k in totals}
        for k in totals:
            totals[k] += counts[k]
        with open(path, "rb") as fh:
            digest = hashlib.sha256(fh.read()).hexdigest()
        print(f"report={path} sha256={digest} timestamp={suite.get('timestamp')} "
              + " ".join(f"{k}={v}" for k, v in counts.items()))

    print("total " + " ".join(f"{k}={v}" for k, v in totals.items()))
    if stale:
        print(f"FAIL: {len(stale)} report(s) older than --not-before {not_before.isoformat()}: {stale}")
        return 1
    if totals["tests"] < min_tests:
        print(f"FAIL: tests {totals['tests']} < minTests {min_tests}")
        return 1
    if totals["failures"] or totals["errors"] or totals["skipped"]:
        print("FAIL: failures/errors/skips present")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
