#!/usr/bin/env python3
"""업무 규칙 대장 ↔ 근거·작업 카드·시험 연결 검사 RULE-TRACE (RULE-TRACE-001, RULE-GOV-001 §2.3, D-GOV-006 ③).

경고 모드: 대장 확정 전이라 게이트를 막지 않는다. 항상 exit 0, 단
  exit 2 = 사용법 오류, --json 파일을 쓸 수 없음, 입력 파일(대장·DECISIONS·questions·tasks)을 읽을 수 없음, 대장 표를 파싱할 수 없음
           (도메인 파일에 규칙 행 0건, 또는 규칙 행이 표준 머리글 표 밖에 있음)
  exit 1 = --strict-domains로 지정한 도메인에 4) 위반이 있음. 기본 비활성 — 사용자 확인 후 도메인별로 켠다.

사용: rule-trace.py [--root DIR] [--json PATH] [--strict-domains BIZ,PAY,…]
검사:
  1) 형식 — docs/rules/<DOM>.md 규칙 표 행(`| BR-<DOM>-<3자리> |`, '## 진단' 절 이후 제외): 열 7개, ID 중복, 접두=파일 도메인,
     상태(확정·제안·Demo값·미결(Q-…[ 후보])[꼬리 메모]·대체됨→BR-…[, BR-…]), 출처(사용자·적용·기준, '—'는 미결만), 대체 대상 존재
  2) 근거 ID — 근거 열과 미결 상태의 D-…(docs/DECISIONS.md 표 첫 열)·Q-…(harness/questions.json id). 'D-X-001·002'(이어 쓴 번호),
     'D-X-001~003'(범위)을 펼친다. 'Q-… 후보'는 아직 없는 질문 후보라 제외. 'D-ID' 같은 자리표시와 없는 한 조각 ID
     ('D-OPS' 계열 표기, 'BOOT1 D-08' 문서 안 번호)는 파싱 불가로 따로 센다
  3) 작업 카드 — harness/tasks.json 각 카드 spec·openItems의 BR-ID 중 확정이 아니거나 대장에 없는 것(착수 전 관문 후보)
  4) 확정 규칙 시험 연결 — 상태 확정 행의 검증 열이 없음/—(NONE), 시험 이름을 읽을 수 없음(UNRECOGNIZED), 적힌 시험을
     저장소 시험 경로에서 찾을 수 없음(NOT_FOUND). 이름은 '::' 앞(파일 부분)만 본다. 시험 경로: apps/*/src/test/**, apps/*/frontend/src/**/*.test.*·src/test/**,
     harness/tests/*.py. Kotlin 이름은 파일명 또는 시험 소스의 class/object 선언으로 찾는다
  5) 대체 규칙 시험 잔존 — 상태 대체됨 행 ID가 시험 소스 문자열에 남아 있는 곳
표준 라이브러리만 쓰고 외부 명령·shell을 실행하지 않는다.
"""
import argparse
import fnmatch
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

DOMAINS = ["BIZ", "PAY", "CAN", "FIN", "WH", "ADM"]
HEADER = ["ID", "규칙", "상태", "출처", "근거", "적용 명세", "검증"]
SOURCES = {"사용자", "적용", "기준"}
RULE_ROW = re.compile(r"^\|\s*BR-[A-Z]+-\d{3}\s*\|")
RULE_ID = re.compile(r"\bBR-([A-Z]+)-(\d{3})(?!\d)")
DIAGNOSIS = re.compile(r"^##\s+진단")
STATUS_PENDING = re.compile(r"^미결\((Q-[A-Za-z0-9-]+)( 후보)?\)(.*)$")
STATUS_SUPERSEDED = re.compile(r"^대체됨\s*→\s*(BR-[A-Z]+-\d{3}(?:\s*,\s*BR-[A-Z]+-\d{3})*)\s*$")
REF = re.compile(r"(?<![A-Za-z0-9-])([DQ])-([A-Za-z0-9]+(?:-[A-Za-z0-9]+)*)")
REF_SHAPE = re.compile(r"^[DQ]-[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*$")
PLACEHOLDERS = {"D-ID", "Q-ID"}
CONTINUATION = re.compile(r"^(\s*)([·~])\s*(\d+)(?![0-9A-Za-z-])")
KOTLIN_TEST = re.compile(r"\b([A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*(?:Spec|Test|Tests|IT))\b")
FRONT_TEST = re.compile(r"([A-Za-z0-9_.*\-]+\.test\.[jt]sx?)\b")
PY_TEST = re.compile(r"\b(test_[a-z0-9_]+\.py)\b")
CLASS_DECL = re.compile(r"^\s*(?:(?:internal|private|abstract|open|data)\s+)*(?:class|object)\s+([A-Za-z0-9_]+)", re.MULTILINE)
NO_TEST = {"없음", "—", "-", ""}


class ParseError(Exception):
    pass


def split_cells(line: str) -> list[str]:
    """'|' 표 행을 칸으로 나눈다. `…` 코드 안의 '|'와 '\\|'는 칸 구분이 아니다."""
    body = line.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|") and not body.endswith("\\|"):
        body = body[:-1]
    cells, current, in_code, i = [], [], False, 0
    while i < len(body):
        ch = body[i]
        if ch == "\\" and i + 1 < len(body) and body[i + 1] == "|":
            current.append("|"); i += 2; continue
        if ch == "`":
            in_code = not in_code
        if ch == "|" and not in_code:
            cells.append("".join(current).strip()); current = []
        else:
            current.append(ch)
        i += 1
    cells.append("".join(current).strip())
    return cells


def parse_ledger(path: Path, domain: str, rel: str) -> list[dict]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as error:
        raise ParseError(f"{rel}: cannot read ({error})")
    rows, header, in_table = [], None, False
    for number, line in enumerate(lines, 1):
        if DIAGNOSIS.match(line):
            break
        if not line.startswith("|"):
            in_table, header = False, None
            continue
        if not in_table:
            in_table, header = True, split_cells(line)
            continue
        if RULE_ROW.match(line):
            if header != HEADER:
                raise ParseError(f"{rel}:{number}: rule row outside a standard rule table (header {header})")
            cells = split_cells(line)
            rows.append({"domain": domain, "file": rel, "line": number, "id": cells[0], "cells": cells})
    if not rows:
        raise ParseError(f"{rel}: no rule rows before '## 진단' (cannot parse rule tables)")
    return rows


def parse_status(text: str):
    """(정규화 상태, 부가정보, 변형 메모) — 모르는 값은 (None, …)."""
    if text in ("확정", "제안", "Demo값"):
        return text, {}, None
    pending = STATUS_PENDING.match(text)
    if pending:
        qid, candidate, tail = pending.group(1), bool(pending.group(2)), pending.group(3).strip()
        notes = [n for n in (("질문 후보" if candidate else None), (f"꼬리 메모: {tail[:60]}" if tail else None)) if n]
        return "미결", {"question": qid, "candidate": candidate}, ("; ".join(notes) or None)
    superseded = STATUS_SUPERSEDED.match(text)
    if superseded:
        targets = [t.strip() for t in superseded.group(1).split(",")]
        return "대체됨", {"targets": targets}, None
    return None, {}, None


def extract_refs(text: str, known: set[str]):
    """근거 칸에서 (참조 ID 목록, 파싱 불가 토큰 목록). '·002'·'~003' 이어 쓴 번호를 펼친다."""
    refs, unparsed = [], []
    for match in REF.finditer(text):
        token = f"{match.group(1)}-{match.group(2)}"
        # 자리표시('D-ID'), 모양이 다른 것, 대문자 한 조각뿐인 계열·문서 안 번호('D-OPS 선례', 'BOOT1 D-08')는
        # 결정/질문 ID로 확인할 수 없어 파싱 불가로 분리한다. 한 조각이어도 실제 있는 ID(Q-CALENDAR 등)는 아래에서 확인된다.
        if token in PLACEHOLDERS or not REF_SHAPE.match(token) or ("-" not in token[2:] and token not in known):
            unparsed.append(token)
            continue
        refs.append(token)
        tail_number = re.search(r"-(\d+)$", token)
        if not tail_number:
            continue
        prefix, width, last = token[:tail_number.start(1)], len(tail_number.group(1)), int(tail_number.group(1))
        rest = text[match.end():]
        while True:
            cont = CONTINUATION.match(rest)
            if not cont:
                break
            number = int(cont.group(3))
            if cont.group(2) == "~":
                refs.extend(f"{prefix}{n:0{width}d}" for n in range(last + 1, number + 1))
            else:
                refs.append(f"{prefix}{cont.group(3)}")
            last = number
            rest = rest[cont.end():]
    return refs, unparsed


def load_decisions(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    return set(re.findall(r"^\|\s*(D-[A-Za-z0-9-]+)\s*\|", text, re.MULTILINE))


def load_questions(path: Path) -> set[str]:
    doc = json.loads(path.read_text(encoding="utf-8"))
    return {q["id"] for q in doc.get("questions", []) if isinstance(q, dict) and "id" in q}


def load_tasks(path: Path) -> list[dict]:
    doc = json.loads(path.read_text(encoding="utf-8"))
    return [t for t in doc.get("tasks", []) if isinstance(t, dict)]


def test_files(root: Path) -> list[Path]:
    files = []
    for app in sorted((root / "apps").glob("*")):
        files += [p for p in sorted((app / "src/test").rglob("*")) if p.is_file()]
        front = app / "frontend/src"
        if front.is_dir():
            files += [p for p in sorted(front.rglob("*")) if p.is_file() and "node_modules" not in p.parts
                      and (re.search(r"\.test\.[jt]sx?$", p.name) or "test" in p.relative_to(front).parts[:-1])]
    files += sorted((root / "harness/tests").glob("*.py"))
    return files


def test_names(cell: str) -> list[str]:
    """검증 칸의 시험 파일 이름. ',' ';'로 나눈 조각마다 '::' 앞(파일 부분)만 본다 — '::' 뒤는 시험 안 이름·설명이다."""
    names = []
    for segment in re.split(r"[,;]", cell):
        file_part = segment.split("::", 1)[0]
        for pattern in (FRONT_TEST, PY_TEST, KOTLIN_TEST):
            for name in pattern.findall(file_part):
                if name not in names:
                    names.append(name)
    return names


def build_index(root: Path, files: list[Path]):
    basenames, classes, texts = set(), set(), {}
    for path in files:
        basenames.add(path.name)
        if path.suffix in (".kt", ".java", ".ts", ".tsx", ".js", ".jsx", ".py"):
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            texts[path.relative_to(root).as_posix()] = text
            if path.suffix in (".kt", ".java"):
                classes.update(CLASS_DECL.findall(text))
    return basenames, classes, texts


def name_exists(name: str, basenames: set[str], classes: set[str]) -> bool:
    if "*" in name:
        return any(fnmatch.fnmatchcase(b, name) for b in basenames)
    if "." in name:
        return name in basenames
    return f"{name}.kt" in basenames or f"{name}.java" in basenames or name in classes


def analyse(root: Path, strict: list[str]) -> dict:
    rows = []
    for domain in DOMAINS:
        rel = f"docs/rules/{domain}.md"
        if not (root / rel).is_file():
            raise ParseError(f"{rel}: missing ledger file")
        rows += parse_ledger(root / rel, domain, rel)
    try:
        decisions = load_decisions(root / "docs/DECISIONS.md")
        questions = load_questions(root / "harness/questions.json")
        tasks = load_tasks(root / "harness/tasks.json")
    except (OSError, ValueError) as error:
        raise ParseError(f"input: {error}")

    fmt, variants, missing, unparsed = [], [], [], []
    status_of, status_counts, seen = {}, Counter(), set()
    by_domain = {d: Counter() for d in DOMAINS}

    def problem(row, kind, detail):
        fmt.append({"id": row["id"], "file": row["file"], "line": row["line"], "kind": kind, "detail": detail})

    for row in rows:
        cells, rid = row["cells"], row["id"]
        if rid in seen:
            problem(row, "DUPLICATE_ID", "ID가 이미 나왔다")
        seen.add(rid)
        if rid.split("-")[1] != row["domain"]:
            problem(row, "DOMAIN_PREFIX", f"파일 도메인 {row['domain']}")
        if len(cells) != len(HEADER):
            problem(row, "COLUMNS", f"열 {len(cells)}개 (7개여야 함)")
            continue
        status, info, note = parse_status(cells[2])
        row["status"], row["info"] = status, info
        if status is None:
            problem(row, "UNKNOWN_STATUS", cells[2][:80])
        else:
            status_of.setdefault(rid, status)
            status_counts[status] += 1
            by_domain[row["domain"]][status] += 1
        if note:
            variants.append({"id": rid, "file": row["file"], "line": row["line"], "raw": cells[2][:120], "note": note})
        source = cells[3]
        if source == "—":
            if status not in (None, "미결"):
                problem(row, "SOURCE_STATUS_MISMATCH", f"출처 — 는 미결 상태에만 (상태 {status})")
        elif source not in SOURCES:
            problem(row, "UNKNOWN_SOURCE", source[:80])
        refs, bad = extract_refs(cells[4], decisions | questions)
        if status == "미결" and not info["candidate"]:
            refs.append(info["question"])
        for ref in dict.fromkeys(refs):
            known = decisions if ref.startswith("D-") else questions
            if ref not in known:
                missing.append({"id": rid, "file": row["file"], "line": row["line"], "ref": ref})
        for token in dict.fromkeys(bad):
            unparsed.append({"id": rid, "file": row["file"], "line": row["line"], "token": token})

    all_ids = {r["id"] for r in rows}
    for row in rows:
        for target in (row.get("info") or {}).get("targets", []):
            if target not in all_ids:
                problem(row, "SUPERSEDED_TARGET_MISSING", target)

    task_refs = []
    for task in tasks:
        fields = [("spec", task.get("spec"))] + [("openItems", item) for item in (task.get("openItems") or [])]
        reported = set()
        for field, value in fields:
            if not isinstance(value, str):
                continue
            for match in RULE_ID.finditer(value):
                rid = match.group(0)
                status = status_of.get(rid, "UNKNOWN" if rid not in all_ids else None)
                if status == "확정" or (field, rid) in reported:
                    continue
                reported.add((field, rid))
                task_refs.append({"task": task.get("id"), "field": field, "ruleId": rid, "status": status})

    files = test_files(root)
    basenames, classes, texts = build_index(root, files)
    no_test, no_test_by_domain, linked_by_id = [], Counter(), Counter()
    confirmed_by_domain = Counter()
    for row in rows:
        if row.get("status") != "확정":
            continue
        confirmed_by_domain[row["domain"]] += 1
        if any(re.search(re.escape(row["id"]) + r"(?!\d)", t) for t in texts.values()):
            linked_by_id[row["domain"]] += 1
        cell = row["cells"][6]
        names = test_names(cell)
        if cell.strip() in NO_TEST or cell.startswith("없음"):
            reason, absent = "NONE", []
        elif not names:
            reason, absent = "UNRECOGNIZED", []
        else:
            absent = [n for n in names if not name_exists(n, basenames, classes)]
            reason = "NOT_FOUND" if absent else None
        if reason:
            no_test.append({"id": row["id"], "domain": row["domain"], "file": row["file"], "line": row["line"],
                            "reason": reason, "missing": absent, "cell": cell[:160]})
            no_test_by_domain[row["domain"]] += 1

    superseded = [r["id"] for r in rows if r.get("status") == "대체됨"]
    leftovers = []
    for rel, text in sorted(texts.items()):
        for number, line in enumerate(text.splitlines(), 1):
            for rid in superseded:
                if re.search(re.escape(rid) + r"(?!\d)", line):
                    leftovers.append({"id": rid, "file": rel, "line": number})

    strict_violations = {d: no_test_by_domain[d] for d in strict if no_test_by_domain[d]}

    def listing(items, **extra):
        return {"count": len(items), **extra, "items": items}

    return {
        "tool": "rule-trace", "mode": "warn" if not strict else "warn+strict", "root": str(root),
        "strictDomains": strict, "strictViolations": strict_violations,
        "rows": len(rows), "ruleIds": [r["id"] for r in rows], "statusCounts": dict(status_counts),
        "byDomain": {d: {"rows": sum(1 for r in rows if r["domain"] == d), **dict(by_domain[d])} for d in DOMAINS},
        "testFilesScanned": len(files),
        "checks": {
            "format": listing(fmt, byKind=dict(Counter(i["kind"] for i in fmt))),
            "statusVariants": listing(variants),
            "evidenceMissing": listing(missing, refs=sorted({i["ref"] for i in missing})),
            "evidenceUnparsed": listing(unparsed),
            "taskRefs": listing(task_refs),
            "confirmedWithoutTest": listing(no_test, byDomain={d: no_test_by_domain[d] for d in DOMAINS},
                                            confirmedByDomain={d: confirmed_by_domain[d] for d in DOMAINS},
                                            byReason=dict(Counter(i["reason"] for i in no_test))),
            "confirmedLinkedByRuleId": {"count": sum(linked_by_id.values()),
                                        "byDomain": {d: linked_by_id[d] for d in DOMAINS}},
            "supersededInTests": listing(leftovers),
        },
    }


def summary(report: dict, limit: int) -> str:
    c = report["checks"]
    out = [f"rule-trace ({report['mode']}) root={report['root']}",
           f"규칙 행 {report['rows']} — 상태 {report['statusCounts']}, 시험 파일 {report['testFilesScanned']}개 조사"]

    def block(title, check, fmt_item):
        out.append(f"\n[{title}: {check['count']}]")
        for item in check["items"][:limit]:
            out.append("  " + fmt_item(item))
        if check["count"] > limit:
            out.append(f"  … 외 {check['count'] - limit}건 (--json 참고)")

    block("1) 형식 위반", c["format"], lambda i: f"{i['file']}:{i['line']} {i['id']} {i['kind']} {i['detail']}")
    block("1) 상태 표기 변형(허용, 참고)", c["statusVariants"], lambda i: f"{i['file']}:{i['line']} {i['id']} {i['note']}")
    block("2) 근거 ID 없음", c["evidenceMissing"], lambda i: f"{i['file']}:{i['line']} {i['id']} → {i['ref']}")
    block("2) 근거 ID 파싱 불가", c["evidenceUnparsed"], lambda i: f"{i['file']}:{i['line']} {i['id']} '{i['token']}'")
    block("3) 작업 카드의 확정 아닌 규칙 참조", c["taskRefs"],
          lambda i: f"{i['task']}.{i['field']} {i['ruleId']} ({i['status']})")
    nt = c["confirmedWithoutTest"]
    block("4) 확정 규칙 시험 연결 없음", nt,
          lambda i: f"{i['file']}:{i['line']} {i['id']} {i['reason']}" + (f" {i['missing']}" if i["missing"] else ""))
    out.append("  도메인별(없음/확정): " + ", ".join(f"{d} {nt['byDomain'][d]}/{nt['confirmedByDomain'][d]}" for d in DOMAINS)
               + f" — 사유 {nt['byReason']}")
    linked = c["confirmedLinkedByRuleId"]
    out.append(f"  (참고) 시험 소스에 BR-ID가 적힌 확정 규칙: {linked['count']} {linked['byDomain']}")
    block("5) 대체 규칙 시험 잔존", c["supersededInTests"], lambda i: f"{i['file']}:{i['line']} {i['id']}")
    out.append(f"\n확정 규칙 시험 연결 없음: {nt['count']}")
    if report["strictDomains"]:
        out.append(f"strict 도메인 {report['strictDomains']} 위반 {report['strictViolations']}")
    out.append("경고 모드 — 게이트를 막지 않는다(D-GOV-006 ③)")
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description="업무 규칙 대장 추적 검사(경고 모드)")
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument("--json", dest="json_path")
    parser.add_argument("--strict-domains", default="", help="사용자 확인 후 활성: 지정 도메인의 4) 위반이면 exit 1")
    parser.add_argument("--limit", type=int, default=30, help="항목별 stdout 목록 상한")
    try:
        args = parser.parse_args()
    except SystemExit:
        return 2
    strict = [d.strip() for d in args.strict_domains.split(",") if d.strip()]
    unknown = [d for d in strict if d not in DOMAINS]
    if unknown:
        print(f"error: unknown domain {', '.join(unknown)} (allowed {','.join(DOMAINS)})", file=sys.stderr)
        return 2
    try:
        report = analyse(Path(args.root).resolve(), strict)
    except ParseError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    exit_code = 1 if report["strictViolations"] else 0
    report["exitCode"] = exit_code
    if args.json_path:
        try:
            Path(args.json_path).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        except OSError as error:
            print(f"error: cannot write --json {args.json_path} ({error})", file=sys.stderr)
            return 2
    print(summary(report, args.limit))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
