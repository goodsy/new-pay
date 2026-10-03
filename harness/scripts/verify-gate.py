#!/usr/bin/env python3
"""고정 검증 gate 실행기 (HARNESS-001).

사용: verify-gate.py <TASK_ID> --expect-fingerprint sha256:<64 hex>   (기대 fingerprint 필수)
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
import shutil
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
GRADLE_USER_FILES = ["gradle.properties", "init.gradle", "init.gradle.kts", "init.d"]
HOME_USER_FILES = ["~/.testcontainers.properties"]


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def file_digest(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as error:
        return f"UNREADABLE: {type(error).__name__}"


def environment_snapshot() -> dict:
    # 값 hash는 salt가 없다. 저엔트로피 비밀은 역추정될 수 있으므로 증거는 로컬 build/에만 둔다.
    names = sorted({n for n in os.environ if n in ENV_INPUTS or any(n.startswith(p) for p in ENV_PREFIXES)})
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME") or os.path.expanduser("~/.gradle"))
    candidates = {str(gradle_home / name): gradle_home / name for name in GRADLE_USER_FILES}
    candidates.update({spec: Path(os.path.expanduser(spec)) for spec in HOME_USER_FILES})
    files = {}
    for label, path in candidates.items():
        if path.is_dir():
            files[label] = {f.name: file_digest(f) for f in sorted(path.iterdir()) if f.is_file()}
        elif path.is_file():
            files[label] = file_digest(path)
    return {"envSha256": {n: sha256_text(os.environ[n]) for n in names}, "userFiles": files}


def archive(command: dict, log_dir: Path, index: int) -> list[dict]:
    """명령이 만든 보고서(예: 테스트 XML)를 증거 디렉터리에 복사한다. 이후 실행이 build/를 덮어써도 남는다."""
    archived = []
    target = log_dir / f"{index}-{command['id']}-files"
    for pattern in command.get("archiveGlobs", []):
        for source in sorted(ROOT.glob(pattern)):
            relative = source.relative_to(ROOT)
            copy = target / relative
            copy.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, copy)
            archived.append({"source": str(relative), "copy": str(copy.relative_to(ROOT)), "sha256": file_digest(copy)})
    return archived


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
            try:
                proc.wait(timeout=POST_KILL_WAIT_SECONDS)
            except subprocess.TimeoutExpired:
                pass
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
    # 기대 fingerprint는 필수다. 없으면 어떤 코드에 대해서도 PASS를 낼 수 있다.
    if (len(args) != 3 or args[1] != "--expect-fingerprint"
            or not TASK_ID.match(args[0]) or not FINGERPRINT_VALUE.match(args[2])):
        print("usage: verify-gate.py <TASK_ID> --expect-fingerprint sha256:<64 hex>", file=sys.stderr)
        return 2
    task_id, expected = args[0], args[2]
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
        result["archived"] = archive(command, log_dir, index) if result["exitCode"] is not None else []
        if command_id == "FINGERPRINT":
            result["codeFingerprint"] = fingerprint_of(output)
        runs.append(result)
        if result["environmentError"] or result["exitCode"] != 0 or result["timedOut"] or result["violations"]:
            break
        if index == 0 and result.get("codeFingerprint") != expected:
            # 다른 코드를 검증하느라 빌드 시간을 쓰지 않는다. 최종 비교는 아래에서 다시 한다.
            problems.append(f"fingerprint {result.get('codeFingerprint')} != expected {expected} (stopped after stage 0)")
            break

    fingerprints = [r.get("codeFingerprint") for r in runs if r["commandId"] == "FINGERPRINT"]
    if len(runs) == len(GATE):
        if len(fingerprints) != 2 or None in fingerprints:
            problems.append("fingerprint missing or malformed")
        elif fingerprints[0] != fingerprints[1]:
            problems.append("fingerprint changed during gate")
        elif fingerprints[0] != expected:
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
