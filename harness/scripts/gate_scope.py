"""영향 범위 게이트의 범위 계산 (PROC-GATE-001, 결정 D-PROC-001 — R-1).

verify-gate.py --scoped가 import한다. 범위는 사람이 고르지 않는다. 바뀐 파일 경로를 commands.json `scopedGate.pathRules`
(정규식, 위에서부터 첫 일치)로 분류해 실행할 단위(unit)를 계산한다.

분류(kind)
  STATE         하네스 진행 기록(tasks/questions/progress, harness/archive). 빌드 입력 아님
  DOC           문서. 빌드 입력 아님(시험이 읽는 문서는 MODULE 규칙으로 먼저 잡는다)
  LOCAL         저장소에 커밋하지 않는 로컬 파일 중 빌드가 읽지 않는 것(다운로드 표식 등)
  HARNESS-TEST  하네스 자체 시험. 단위는 없지만 HARNESS-SELFTEST가 항상 돌고 빌드 입력에 포함
  MODULE        units에 적은 단위의 시험을 돌린다
  (일치 없음)    FULL — 영향 범위를 확정할 수 없다. 전체 게이트가 필요하다(fail-safe)

buildFingerprint: fingerprint.sh --manifest 줄 중 STATE·DOC·LOCAL이 아닌 경로만으로 만든 SHA-256.
값이 같으면 빌드·시험 입력이 같다는 뜻이다(문서만 바뀐 경우 재게이트 불필요 — 결정 D-PROC-001 1-2).
"""
import hashlib
import re
import subprocess
from pathlib import Path

REVISION = re.compile(r"^[0-9a-f]{7,40}$")
FULL = "FULL"
NOT_BUILD_INPUT = {"STATE", "DOC", "LOCAL"}
KINDS = NOT_BUILD_INPUT | {"HARNESS-TEST", "MODULE"}
MANIFEST_LINE = re.compile(r"^(644|755) ([0-9a-f]{64}) (.+)$")


class ScopeError(Exception):
    """범위를 계산할 수 없음(git 실패, 잘못된 규칙). verify-gate는 ENVIRONMENT_ERROR가 아니라 FAIL로 다룬다."""


def validate(section: dict) -> list[str]:
    """scopedGate 정의 자체의 결함 목록. 빈 목록이면 사용 가능."""
    problems = []
    units = section.get("units", {})
    if not units:
        problems.append("scopedGate.units is empty")
    for index, rule in enumerate(section.get("pathRules", [])):
        kind = rule.get("kind")
        if kind not in KINDS:
            problems.append(f"rule {index}: unknown kind {kind}")
        try:
            re.compile(rule.get("pattern", ""))
        except re.error as error:
            problems.append(f"rule {index}: bad pattern ({error})")
        listed = rule.get("units", []) + rule.get("newDirectoryAlsoUnits", [])
        for unit in listed:
            if unit not in units:
                problems.append(f"rule {index}: unknown unit {unit}")
        if kind == "MODULE" and not rule.get("units"):
            problems.append(f"rule {index}: MODULE rule without units")
        if kind != "MODULE" and listed:
            problems.append(f"rule {index}: {kind} rule must not list units")
    if not section.get("pathRules"):
        problems.append("scopedGate.pathRules is empty")
    return problems


def classify(path: str, rules: list[dict]) -> tuple[str, dict | None]:
    for rule in rules:
        if re.search(rule["pattern"], path):
            return rule["kind"], rule
    return FULL, None


def git(root: Path, *args: str) -> str:
    proc = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)
    if proc.returncode != 0:
        raise ScopeError(f"git {args[0]} failed: {proc.stderr.strip()[:300]}")
    return proc.stdout


def resolve_base(root: Path, base: str) -> str:
    if base != "HEAD" and not REVISION.match(base):
        raise ScopeError(f"base must be HEAD or a hex revision, got {base!r}")
    sha = git(root, "rev-parse", "--verify", "--quiet", f"{base}^{{commit}}").strip()
    # base는 현재 HEAD의 조상(또는 HEAD)이어야 한다. 다른 갈래의 커밋을 주면 HEAD에 들어온 변경이 범위에서 빠질 수 있다.
    if subprocess.run(["git", "merge-base", "--is-ancestor", sha, "HEAD"], cwd=root, capture_output=True).returncode != 0:
        raise ScopeError(f"base {sha} is not an ancestor of HEAD")
    return sha


def changed_paths(root: Path, base_sha: str) -> list[str]:
    """base 커밋 대비 작업 트리의 변경(수정·삭제·추가) + 미추적(비무시) 파일. rename은 옛 경로·새 경로를 모두 센다."""
    diff = git(root, "diff", "--no-renames", "--name-only", "-z", base_sha, "--")
    untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z")
    return sorted({p for p in (diff + untracked).split("\0") if p})


def sha_short(sha: str) -> str:
    return sha[:12]


def directory_is_new(root: Path, base_sha: str, path: str) -> bool:
    parent = str(Path(path).parent)
    return git(root, "ls-tree", "-d", "--name-only", base_sha, "--", parent).strip() == ""


def compute(root: Path, base: str, section: dict) -> dict:
    problems = validate(section)
    if problems:
        raise ScopeError("; ".join(problems))
    base_sha = resolve_base(root, base)
    rules = section["pathRules"]
    order = list(section["units"])
    selected, full_required, entries = set(), [], []
    paths = changed_paths(root, base_sha)
    if not paths:
        # 변경 0건은 "검사할 것 없음"이지 성공이 아니다. 이미 커밋한 배치라면 그 배치 앞 커밋(직전 게이트 headRevision)을 base로 준다.
        raise ScopeError(f"no changed paths against base {sha_short(base_sha)}; committed batches need --base <revision before them>")
    for path in paths:
        kind, rule = classify(path, rules)
        units = list(rule.get("units", [])) if rule else []
        also = rule.get("newDirectoryAlsoUnits", []) if rule else []
        if also and directory_is_new(root, base_sha, path):
            units += [u for u in also if u not in units]
        if kind == FULL:
            full_required.append(path)
        selected.update(units)
        entries.append({"path": path, "kind": kind, "units": units})
    if all(e["kind"] == "STATE" for e in entries):
        # 진행 기록(tasks/questions.json)만 바뀐 것은 검사할 배치가 아니다. 커밋 뒤 기록만 고치고 base 없이 돌려
        # 커밋된 배치가 시험 없이 PASS하는 일을 막는다(재리뷰 m-A).
        raise ScopeError(f"only harness records changed against base {sha_short(base_sha)}; "
                         "committed batches need --base <revision before them>")
    build_input = any(e["kind"] not in NOT_BUILD_INPUT for e in entries)
    return {
        "base": base, "baseRevision": base_sha, "paths": entries,
        "units": [u for u in order if u in selected],
        "fullGateRequiredBy": full_required,
        # False면 문서·로컬 파일만 바뀐 범위다(DOCS_ONLY). 시험을 돌린 배치 증거로 쓰지 않는다 — HARNESS-GUIDE §11.
        "buildInputChanged": build_input,
        "classification": "FULL" if full_required else ("BUILD" if build_input else "DOCS_ONLY"),
    }


def build_fingerprint(manifest_output: str, rules: list[dict]) -> str | None:
    lines = [m for m in (MANIFEST_LINE.match(l) for l in manifest_output.splitlines()) if m]
    if not lines:
        return None
    kept = [m.group(0) for m in lines if classify(m.group(3), rules)[0] not in NOT_BUILD_INPUT]
    return "sha256:" + hashlib.sha256(("\n".join(kept) + "\n").encode("utf-8")).hexdigest()
