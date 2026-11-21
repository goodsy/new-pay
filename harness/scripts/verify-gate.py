#!/usr/bin/env python3
"""고정 검증 gate 실행기 (HARNESS-001).

사용:
  verify-gate.py <TASK_ID> --expect-fingerprint sha256:<64 hex>                          전체 게이트(FULL)
  verify-gate.py <TASK_ID> --scoped [--base <HEAD|hex rev>] --expect-fingerprint sha256:<64 hex>   영향 범위 게이트(SCOPED)
commands.json에 등록된 gate 순서를 shell 없이 argv로 실행하고 증거를 남긴다.
FULL:
  FINGERPRINT → HARNESS-SELFTEST → GRADLE-CHECK → TEST-SUMMARY(--not-before 자동) → TEST-SUMMARY-FRONTEND(같은 not-before,
  프론트 Vitest 보고서 별도 하한 — ADMIN-BOOT-001 S1 리뷰 M2(a)) → IMAGE-DIGESTS → FINGERPRINT
SCOPED (PROC-GATE-001, 결정 D-PROC-001 — R-1):
  FINGERPRINT → SCOPE(바뀐 경로 → 단위, gate_scope.py) → HARNESS-SELFTEST → GRADLE-SCOPED(선택 단위의 등록 task만)
  → TEST-SUMMARY:<단위>(단위별 하한, 같은 not-before) → IMAGE-DIGESTS(컨테이너 단위가 있을 때) → FINGERPRINT
  범위를 확정할 수 없는 경로(공통 라이브러리·빌드 스크립트·하네스 등)가 하나라도 있으면 빌드 없이 FULL_GATE_REQUIRED.
  단위의 시험 묶음은 그 모듈 시험 전체이며 줄이지 않는다. 기능 완료(DONE)는 여전히 FULL PASS가 필요하다.
바뀌는 값은 TASK_ID(증거 파일명), NOT_BEFORE_UTC(스크립트가 생성), base revision(형식 검사)뿐이다.
단위 task·보고서·하한은 commands.json에서만 온다. 모델/사용자 문자열을 argv에 넣지 않는다.

verdict:
  PASS                모든 단계 exit 0, timeout 없음, 출력 규칙 충족, fingerprint 전/후가 값이 있고 같으며 기대값과 일치
  FAIL                실제 검사 실패(단계 실패, timeout, 출력 규칙 위반, fingerprint 불일치/누락, 범위 계산 실패)
  ENVIRONMENT_ERROR   실행 파일 없음 등으로 명령을 시작하지 못함
  FULL_GATE_REQUIRED  (SCOPED만) 영향 범위를 확정할 수 없음. 전체 게이트를 돌려야 한다. 검증 성공이 아니다
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
sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_scope  # noqa: E402  같은 디렉터리의 범위 계산 모듈

GATE =["FINGERPRINT", "HARNESS-SELFTEST", "GRADLE-CHECK", "TEST-SUMMARY", "TEST-SUMMARY-FRONTEND", "IMAGE-DIGESTS",
        "FINGERPRINT"]
TASK_ID = re.compile(r"^[A-Z][A-Z0-9-]{1,40}$")
FINGERPRINT_VALUE = re.compile(r"^sha256:[0-9a-f]{64}$")
POST_KILL_WAIT_SECONDS = 10
# 판정에 영향을 줄 수 있는 저장소 밖 입력. 값은 비밀일 수 있으므로 이름과 hash만 남긴다.
ENV_INPUTS = ["JAVA_HOME", "GRADLE_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "GRADLE_USER_HOME", "DOCKER_HOST"]
ENV_PREFIXES = ["TESTCONTAINERS_", "ORG_GRADLE_PROJECT_"]
GRADLE_USER_FILES = ["gradle.properties", "init.gradle", "init.gradle.kts", "init.d"]
HOME_USER_FILES = ["~/.testcontainers.properties", "~/.npmrc"]
# npm 설정 환경변수(대소문자 무관, ADMIN-BOOT-001 S0 §17.7). 값은 registry 토큰일 수 있어 hash도 남기지 않고 이름만 기록한다.
# 프론트 Gradle task는 이 변수들을 제거한 환경으로 npm을 실행한다 — 기록은 그 격리가 필요했던 입력의 흔적이다.
NAME_ONLY_ENV_PREFIX = "npm_config_"


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
    npm_names = sorted(n for n in os.environ if n.lower().startswith(NAME_ONLY_ENV_PREFIX))
    return {"envSha256": {n: sha256_text(os.environ[n]) for n in names}, "npmEnvNames": npm_names, "userFiles": files}


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


USAGE = ("usage: verify-gate.py <TASK_ID> [--scoped [--base <HEAD|hex rev>]] "
         "--expect-fingerprint sha256:<64 hex>")


def parse_args(args: list[str]):
    """(task_id, expected, scoped, base) 또는 None. 기대 fingerprint는 필수다(없으면 어떤 코드에도 PASS를 낼 수 있다)."""
    if not args or not TASK_ID.match(args[0]):
        return None
    task_id, rest, scoped, base = args[0], args[1:], False, "HEAD"
    if rest[:1] == ["--scoped"]:
        scoped, rest = True, rest[1:]
        if rest[:1] == ["--base"]:
            if len(rest) < 2 or not (rest[1] == "HEAD" or gate_scope.REVISION.match(rest[1])):
                return None
            base, rest = rest[1], rest[2:]
    if len(rest) != 2 or rest[0] != "--expect-fingerprint" or not FINGERPRINT_VALUE.match(rest[1]):
        return None
    return task_id, rest[1], scoped, base


def execute(command: dict, argv: list[str], log_dir: Path, index: int, label: str) -> dict:
    result = run(command, argv)
    output = result.pop("output")
    result["commandId"] = label
    log = log_dir / f"{index}-{label.replace(':', '_')}.log"
    log.write_text(output, encoding="utf-8")
    result.update(log=str(log.relative_to(ROOT)), logSha256=sha256_text(output), outputTail=output[-4000:],
                  violations=output_violations(command, output) if result["exitCode"] == 0 else [])
    result["archived"] = archive(command, log_dir, index) if result["exitCode"] is not None else []
    result["_output"] = output
    return result


def failed(result: dict) -> bool:
    return bool(result["environmentError"] or result["exitCode"] != 0 or result["timedOut"] or result["violations"])


def scoped_plan(section: dict, units: list[str]) -> tuple[dict, list[str], list[dict]]:
    """선택 단위의 GRADLE-SCOPED 명령 정의, Gradle argv, 단위별 TEST-SUMMARY 명령 목록. 모두 registry 값만 쓴다."""
    defs = [section["units"][u] for u in units]
    tasks = []
    for d in defs:
        tasks += [t for t in d["tasks"] if t not in tasks]
    gradle = {
        "id": "GRADLE-SCOPED", "timeoutSeconds": section["gradleTimeoutSeconds"],
        "outputMustMatch": ["^BUILD SUCCESSFUL"] + [f"^> Task {re.escape(t)}$" for t in tasks],
        "outputMustNotMatch": list(section["outputMustNotMatch"]),
        "archiveGlobs": [g for d in defs for g in d.get("archiveGlobs", [])],
    }
    summaries = []
    for unit, d in zip(units, defs):
        argv = []
        for token in section["summaryArgv"]:
            if token == "MIN_TESTS":
                argv.append(str(d["minTests"]))
            elif token == "REPORT_DIRS":
                argv += d["reports"]
            else:
                argv.append(token)
        summaries.append({"id": f"TEST-SUMMARY:{unit}", "timeoutSeconds": section["summaryTimeoutSeconds"], "argv": argv})
    return gradle, list(section["gradleArgv"]) + tasks, summaries


def write_evidence(out_dir: Path, task_id: str, stamp: str, evidence: dict, runs: list[dict], problems: list[str]) -> int:
    out = out_dir / f"{task_id}-{stamp}.json"
    out.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    verdict = evidence["verdict"]
    print(f"verdict={verdict}")
    if evidence.get("scope"):
        # DOCS_ONLY PASS는 시험을 돌린 배치 증거가 아니다(HARNESS-GUIDE §11).
        print(f"scope={evidence['scope']['classification']} units={','.join(evidence['scope']['units']) or '-'}")
    print(f"evidence={out.relative_to(ROOT)}")
    for problem in problems:
        print(f"problem: {problem}")
    for r in runs:
        print(f"{r['commandId']} exit={r['exitCode']} timedOut={r['timedOut']} "
              f"violations={r['violations']} envError={r['environmentError']} {r['start']}..{r['end']}")
    return {"PASS": 0, "FAIL": 1, "ENVIRONMENT_ERROR": 3, "FULL_GATE_REQUIRED": 4}[verdict]


def revision_fields(runs: list[dict]) -> dict:
    first = runs[0]["outputTail"] if runs else ""
    fields = {}
    for key in ("headRevision", "dirty"):
        match = re.search(rf"^{key}=(.*)$", first, re.MULTILINE)
        fields[key] = match.group(1) if match else None
    return fields


def main_scoped(task_id: str, expected: str, base: str, registry: dict, commands: dict,
                out_dir: Path, log_dir: Path, stamp: str) -> int:
    section = registry.get("scopedGate") or {}
    rules = section.get("pathRules", [])
    runs, problems, not_before, scope, build_fp, index = [], [], None, None, None, 0
    verdict = None

    def step(command: dict, argv: list[str], label: str) -> bool:
        nonlocal index
        result = execute(command, argv, log_dir, index, label)
        index += 1
        output = result.pop("_output")
        if command["id"] == "FINGERPRINT":
            result["codeFingerprint"] = fingerprint_of(output)
            result["buildFingerprint"] = gate_scope.build_fingerprint(output, rules)
        runs.append(result)
        return not failed(result)

    ok = step(commands["FINGERPRINT"], list(commands["FINGERPRINT"]["argv"]), "FINGERPRINT")
    if ok and runs[0].get("codeFingerprint") != expected:
        problems.append(f"fingerprint {runs[0].get('codeFingerprint')} != expected {expected} (stopped after stage 0)")
        ok = False
    if ok:
        try:
            scope = gate_scope.compute(ROOT, base, section)
        except gate_scope.ScopeError as error:
            problems.append(f"scope: {error}")
            ok = False
    if ok and scope["fullGateRequiredBy"]:
        verdict = "FULL_GATE_REQUIRED"
        problems.append(f"full gate required by {len(scope['fullGateRequiredBy'])} path(s): "
                        + ", ".join(scope["fullGateRequiredBy"][:10]))
        ok = False
    if ok:
        ok = step(commands["HARNESS-SELFTEST"], list(commands["HARNESS-SELFTEST"]["argv"]), "HARNESS-SELFTEST")
    if ok and scope["units"]:
        gradle, gradle_argv, summaries = scoped_plan(section, scope["units"])
        not_before = utc_now()
        ok = step(gradle, gradle_argv, "GRADLE-SCOPED")
        for summary in summaries:
            if not ok:
                break
            argv = [not_before if t == "NOT_BEFORE_UTC" else t for t in summary["argv"]]
            ok = step(summary, argv, summary["id"])
        if ok and any(section["units"][u].get("usesContainers") for u in scope["units"]):
            ok = step(commands["IMAGE-DIGESTS"], list(commands["IMAGE-DIGESTS"]["argv"]), "IMAGE-DIGESTS")
    if ok:
        ok = step(commands["FINGERPRINT"], list(commands["FINGERPRINT"]["argv"]), "FINGERPRINT")

    fingerprints = [r.get("codeFingerprint") for r in runs if r["commandId"] == "FINGERPRINT"]
    builds = [r.get("buildFingerprint") for r in runs if r["commandId"] == "FINGERPRINT"]
    if ok:
        if len(fingerprints) != 2 or None in fingerprints:
            problems.append("fingerprint missing or malformed")
        elif fingerprints[0] != fingerprints[1]:
            problems.append("fingerprint changed during gate")
        elif fingerprints[0] != expected:
            problems.append(f"fingerprint {fingerprints[0]} != expected {expected}")
        else:
            build_fp = builds[0] if builds[0] == builds[1] else None
            if builds[0] != builds[1]:
                problems.append("build fingerprint changed during gate")

    if verdict is None:
        if any(r["environmentError"] for r in runs):
            verdict = "ENVIRONMENT_ERROR"
        elif ok and not problems:
            verdict = "PASS"
        else:
            verdict = "FAIL"

    evidence = {
        "taskId": task_id, "mode": "SCOPED", "verdict": verdict, "problems": problems,
        "scope": scope, "expectedFingerprint": expected,
        "codeFingerprintBefore": fingerprints[0] if fingerprints else None,
        "codeFingerprintAfter": fingerprints[1] if len(fingerprints) > 1 else None,
        "buildFingerprint": build_fp,
        "notBefore": not_before, "environment": environment_snapshot(), "runs": runs,
        "note": "SCOPED PASS는 선택 단위의 검증 증거일 뿐이다. 기능 DONE은 FULL PASS가 필요하다(문서만 바뀐 경우 같은 buildFingerprint의 FULL PASS 시험 증거 재사용, 명세가 바뀌었으면 리뷰 재대조 — HARNESS-GUIDE §11).",
        **revision_fields(runs),
    }
    return write_evidence(out_dir, task_id, stamp, evidence, runs, problems)


def main() -> int:
    parsed = parse_args(sys.argv[1:])
    if parsed is None:
        print(USAGE, file=sys.stderr)
        return 2
    task_id, expected, scoped, base = parsed
    registry = json.loads((ROOT / "harness/commands.json").read_text(encoding="utf-8"))
    commands = {c["id"]: c for c in registry["commands"]}

    stamp = utc_now().replace(":", "")
    out_dir = ROOT / "build/harness-evidence"
    log_dir = out_dir / f"{task_id}-{stamp}-logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    if scoped:
        return main_scoped(task_id, expected, base, registry, commands, out_dir, log_dir, stamp)

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
            result["buildFingerprint"] = gate_scope.build_fingerprint(
                output, (registry.get("scopedGate") or {}).get("pathRules", []))
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

    builds = [r.get("buildFingerprint") for r in runs if r["commandId"] == "FINGERPRINT"]
    evidence = {
        "taskId": task_id, "mode": "FULL", "verdict": verdict, "gate": GATE, "problems": problems,
        "expectedFingerprint": expected,
        "codeFingerprintBefore": fingerprints[0] if fingerprints else None,
        "codeFingerprintAfter": fingerprints[1] if len(fingerprints) > 1 else None,
        # 빌드·시험 입력만의 fingerprint(문서·기록 제외). 전/후가 같을 때만 남긴다. 판정에는 쓰지 않는다(D-PROC-001 1-2).
        "buildFingerprint": builds[0] if len(builds) == 2 and builds[0] == builds[1] else None,
        "notBefore": not_before, "environment": environment_snapshot(), "runs": runs,
        "note": "gate PASS는 검증 증거일 뿐 DONE이 아니다. Reviewer 판정과 Coordinator 확인이 필요하다.",
        **revision_fields(runs),
    }
    return write_evidence(out_dir, task_id, stamp, evidence, runs, problems)


if __name__ == "__main__":
    sys.exit(main())
