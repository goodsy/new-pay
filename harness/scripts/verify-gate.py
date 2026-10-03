#!/usr/bin/env python3
"""고정 검증 gate 실행기 (HARNESS-001).

사용: verify-gate.py <TASK_ID> [--expect-fingerprint sha256:<hex>]
commands.json에 등록된 gate 순서를 shell 없이 argv로 실행하고 증거를 남긴다.
  FINGERPRINT → HARNESS-SELFTEST → GRADLE-CHECK → TEST-SUMMARY(--not-before 자동) → IMAGE-DIGESTS → FINGERPRINT
바뀌는 값은 TASK_ID(증거 파일명)와 NOT_BEFORE_UTC(스크립트가 생성)뿐이다. 모델/사용자 문자열을 argv에 넣지 않는다.

verdict:
  PASS              모든 단계 exit 0, timeout 없음, 출력 규칙 충족, fingerprint 전/후가 값이 있고 같으며 기대값과 일치
  FAIL              실제 검사 실패(단계 실패, timeout, 출력 규칙 위반, fingerprint 불일치/누락)
  ENVIRONMENT_ERROR 실행 파일 없음 등으로 명령을 시작하지 못함
이 스크립트는 DONE을 판정하지 않는다. Verifier가 보고하고 Coordinator가 판정한다.
"""
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GATE = ["FINGERPRINT", "HARNESS-SELFTEST", "GRADLE-CHECK", "TEST-SUMMARY", "IMAGE-DIGESTS", "FINGERPRINT"]
TASK_ID = re.compile(r"^[A-Z][A-Z0-9-]{1,40}$")
FINGERPRINT_VALUE = re.compile(r"^sha256:[0-9a-f]{64}$")
POST_KILL_WAIT_SECONDS = 10
# 판정에 영향을 줄 수 있는 저장소 밖 입력. 값은 비밀일 수 있으므로 이름과 hash만 남긴다.
ENV_INPUTS = ["JAVA_HOME", "GRADLE_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "GRADLE_USER_HOME", "DOCKER_HOST"]
ENV_PREFIXES = ["TESTCONTAINERS_", "ORG_GRADLE_PROJECT_"]
USER_FILES = ["~/.gradle/gradle.properties", "~/.gradle/init.gradle", "~/.gradle/init.gradle.kts",
              "~/.gradle/init.d", "~/.testcontainers.properties"]


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def environment_snapshot() -> dict:
    names = sorted({n for n in os.environ if n in ENV_INPUTS or any(n.startswith(p) for p in ENV_PREFIXES)})
    files = {}
    for spec in USER_FILES:
        path = Path(os.path.expanduser(spec))
        if path.is_dir():
            files[spec] = {f.name: hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(path.iterdir()) if f.is_file()}
        elif path.is_file():
            files[spec] = hashlib.sha256(path.read_bytes()).hexdigest()
    return {"envSha256": {n: sha256_text(os.environ[n]) for n in names}, "userFiles": files}


def run(command: dict, argv: list[str]) -> dict:
    result = {"commandId": command["id"], "argv": argv, "start": utc_now(),
              "exitCode": None, "timedOut": False, "cleanupUnverified": False, "environmentError": None}
    try:
        proc = subprocess.Popen(argv, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, start_new_session=True)
    except OSError as error:
        result.update(end=utc_now(), environmentError=f"{type(error).__name__}: {error}", output="")
        return result
    try:
        output, _ = proc.communicate(timeout=command["timeoutSeconds"])
    except subprocess.TimeoutExpired:
        result["timedOut"] = True
        try:
            os.killpg(proc.pid, signal.SIGKILL)  # gradle wrapper가 띄운 자식 JVM까지 정리
        except ProcessLookupError:
            pass
        try:
            output, _ = proc.communicate(timeout=POST_KILL_WAIT_SECONDS)
        except subprocess.TimeoutExpired:
            # 그룹을 벗어난 자손이 pipe를 잡고 있으면 더 기다리지 않는다.
            result["cleanupUnverified"] = True
            proc.stdout.close()
            proc.kill()
            proc.wait(timeout=POST_KILL_WAIT_SECONDS)
            output = ""
    result.update(end=utc_now(), exitCode=proc.returncode, output=output)
    return result


def output_violations(command: dict, output: str) -> list[str]:
    violations = []
    for pattern in command.get("outputMustMatch", []):
        if not re.search(pattern, output, re.MULTILINE):
            violations.append(f"missing /{pattern}/")
    for pattern in command.get("outputMustNotMatch", []):
        if re.search(pattern, output, re.MULTILINE):
            violations.append(f"forbidden /{pattern}/")
    count_regex = command.get("countRegex")
    if count_regex:
        found = re.search(count_regex, output, re.MULTILINE)
        count = int(found.group(1)) if found else 0
        if count < command["minTests"]:
            violations.append(f"count {count} < minTests {command['minTests']}")
    return violations


def fingerprint_of(output: str):
    for line in output.splitlines():
        if line.startswith("codeFingerprint="):
            value = line.split("=", 1)[1]
            return value if FINGERPRINT_VALUE.match(value) else None
    return None


def main() -> int:
    args = sys.argv[1:]
    expected = None
    if len(args) == 3 and args[1] == "--expect-fingerprint":
        expected = args[2]
        args = args[:1]
    if len(args) != 1 or not TASK_ID.match(args[0]) or (expected is not None and not FINGERPRINT_VALUE.match(expected)):
        print("usage: verify-gate.py <TASK_ID> [--expect-fingerprint sha256:<hex>]", file=sys.stderr)
        return 2
    task_id = args[0]
    commands = {c["id"]: c for c in json.loads((ROOT / "harness/commands.json").read_text(encoding="utf-8"))["commands"]}

    stamp = utc_now().replace(":", "")
    out_dir = ROOT / "build/harness-evidence"
    log_dir = out_dir / f"{task_id}-{stamp}-logs"
    log_dir.mkdir(parents=True, exist_ok=True)

    runs, not_before, problems = [], None, []
    for index, command_id in enumerate(GATE):
        command = commands[command_id]
        argv = list(command["argv"])
        if command_id == "GRADLE-CHECK":
            not_before = utc_now()
        if "NOT_BEFORE_UTC" in argv:
            if not_before is None:
                problems.append(f"{command_id}: NOT_BEFORE_UTC before GRADLE-CHECK")
                break
            argv[argv.index("NOT_BEFORE_UTC")] = not_before
        result = run(command, argv)
        output = result.pop("output")
        log = log_dir / f"{index}-{command_id}.log"
        log.write_text(output, encoding="utf-8")
        result.update(log=str(log.relative_to(ROOT)), logSha256=sha256_text(output), outputTail=output[-4000:],
                      violations=output_violations(command, output) if result["exitCode"] == 0 else [])
        if command_id == "FINGERPRINT":
            result["codeFingerprint"] = fingerprint_of(output)
        runs.append(result)
        if result["environmentError"] or result["exitCode"] != 0 or result["timedOut"] or result["violations"]:
            break

    fingerprints = [r.get("codeFingerprint") for r in runs if r["commandId"] == "FINGERPRINT"]
    if len(runs) == len(GATE):
        if len(fingerprints) != 2 or None in fingerprints:
            problems.append("fingerprint missing or malformed")
        elif fingerprints[0] != fingerprints[1]:
            problems.append("fingerprint changed during gate")
        elif expected is not None and fingerprints[0] != expected:
            problems.append(f"fingerprint {fingerprints[0]} != expected {expected}")
    complete = len(runs) == len(GATE) and all(
        r["exitCode"] == 0 and not r["timedOut"] and not r["violations"] for r in runs)

    if any(r["environmentError"] for r in runs):
        verdict = "ENVIRONMENT_ERROR"
    elif complete and not problems:
        verdict = "PASS"
    else:
        verdict = "FAIL"

    evidence = {
        "taskId": task_id, "verdict": verdict, "gate": GATE, "problems": problems,
        "expectedFingerprint": expected,
        "codeFingerprintBefore": fingerprints[0] if fingerprints else None,
        "codeFingerprintAfter": fingerprints[1] if len(fingerprints) > 1 else None,
        "notBefore": not_before, "environment": environment_snapshot(), "runs": runs,
        "note": "gate PASS는 검증 증거일 뿐 DONE이 아니다. Reviewer 판정과 Coordinator 확인이 필요하다.",
    }
    first = runs[0]["outputTail"] if runs else ""
    for key in ("headRevision", "dirty"):
        match = re.search(rf"^{key}=(.*)$", first, re.MULTILINE)
        evidence[key] = match.group(1) if match else None

    out = out_dir / f"{task_id}-{stamp}.json"
    out.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"verdict={verdict}")
    print(f"evidence={out.relative_to(ROOT)}")
    for problem in problems:
        print(f"problem: {problem}")
    for r in runs:
        print(f"{r['commandId']} exit={r['exitCode']} timedOut={r['timedOut']} "
              f"violations={r['violations']} envError={r['environmentError']} {r['start']}..{r['end']}")
    return {"PASS": 0, "FAIL": 1, "ENVIRONMENT_ERROR": 3}[verdict]


if __name__ == "__main__":
    sys.exit(main())
