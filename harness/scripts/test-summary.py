#!/usr/bin/env python3
"""JUnit XML 보고서 요약 (HARNESS-GUIDE §7).

사용: test-summary.py --not-before <UTC ISO-8601> <min_tests> <report_dir> [<report_dir> ...]
보고서 없음/테스트 수 미달/실패·오류·skip 존재 시 exit 1. 0건은 성공이 아니다.
보고서 실행 timestamp가 --not-before 이전이면(build cache 복원 등 오래된 결과) exit 1.
--not-before 값은 WSL 안에서 `date -u +%Y-%m-%dT%H:%M:%SZ`로 GRADLE-CHECK 직전에 얻는다(호스트/WSL 시계 차이 배제).
Gradle JUnit XML timestamp는 offset 없는 UTC다(2026-10-03 Verifier 실행에서 실행 구간 11:49:33Z–11:50:59Z 안의 값으로 확인).
보고서 루트는 Gradle 형식 <testsuite> 하나 또는 Vitest 형식 <testsuites>(ADMIN-BOOT-001 S1)다. <testsuites> 루트에는 timestamp·skipped가
없으므로(S0 실측) 자식 <testsuite>마다 timestamp 신선도와 tests/failures/errors/skipped를 읽는다. 자식이 없거나 다른 루트면 FAIL.
판정 기준(0건·하한·실패·오류·skip·신선도)은 두 형식에 똑같이 적용한다.
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


def suites_of(root: ET.Element) -> list[ET.Element]:
    """판정 단위 suite 목록. Gradle은 루트 자신, Vitest는 루트 <testsuites>의 자식. 그 밖의 루트는 빈 목록(FAIL)."""
    if root.tag == "testsuite":
        return [root]
    if root.tag == "testsuites":
        return root.findall("testsuite")
    return []


def counted_from_testcases(suite: ET.Element) -> dict:
    """<testcase> 자식을 직접 센 값. suite 합계 속성과 다르면 보고서가 실패·skip을 숨긴 것으로 보고 FAIL한다(S1 리뷰 n1)."""
    cases = suite.findall("testcase")
    return {"tests": len(cases),
            "failures": sum(1 for c in cases if c.find("failure") is not None),
            "errors": sum(1 for c in cases if c.find("error") is not None),
            "skipped": sum(1 for c in cases if c.find("skipped") is not None)}


def oldest(timestamps: list) -> str:
    if any(ts is None for ts in timestamps):
        return "None"
    return min(timestamps, key=parse_utc) if timestamps else "None"


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
    stale, malformed, inconsistent = [], [], []
    for path in files:
        root = ET.parse(path).getroot()
        suites = suites_of(root)
        if not suites:
            malformed.append(path)
        if any(counted_from_testcases(s) != {k: int(s.get(k, 0)) for k in totals} for s in suites):
            inconsistent.append(path)
        counts = {k: sum(int(s.get(k, 0)) for s in suites) for k in totals}
        timestamps = [s.get("timestamp") for s in suites]
        if any(ts is None or parse_utc(ts) < not_before for ts in timestamps):
            stale.append(path)
        for k in totals:
            totals[k] += counts[k]
        with open(path, "rb") as fh:
            digest = hashlib.sha256(fh.read()).hexdigest()
        shown = timestamps[0] if len(timestamps) == 1 else f"{len(suites)} suites, oldest {oldest(timestamps)}"
        print(f"report={path} sha256={digest} timestamp={shown} "
              + " ".join(f"{k}={v}" for k, v in counts.items()))

    print("total " + " ".join(f"{k}={v}" for k, v in totals.items()))
    if malformed:
        print(f"FAIL: {len(malformed)} report(s) without a <testsuite>: {malformed}")
        return 1
    if inconsistent:
        print(f"FAIL: {len(inconsistent)} report(s) whose suite totals disagree with their <testcase> elements: {inconsistent}")
        return 1
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
