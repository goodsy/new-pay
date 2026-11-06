#!/usr/bin/env python3
"""ACQUIRING-001 FINANCIAL ↔ 가상 매입사 Simulator 실제 프로세스 E2E(Coordinator 결정 (6) — GRADLE-CHECK 밖, Demo 스택에서 Coordinator가 실행).

한 줄기: 봉인(financial-batch 스케줄러 — Job) → SFTP 업로드(시도별 임시명 → rename) → Simulator Worker 수신·판정 → outbox ACK·결과 게시
→ FINANCIAL 결과 수신·반영(수신 축·레코드 결과 append-only) [→ 결과 마감 UNKNOWN → 늦은 결과 자동 반영].
이 스크립트는 프로세스를 띄우지 않는다. 띄워진 Demo 스택을 **읽기만** 해서(FINANCIAL DB runtime 계정 SELECT) 기대 상태가 될 때까지 기다린다.

사용(argv 고정 — 셸 문자열을 만들지 않는다):
  python3 harness/e2e/acquiring_e2e.py --scenario {normal,reject,missing-result} --acquirer SIMACQA --business-date 2026-11-05 \
      --amount 12000 [--timeout-seconds 900] [--poll-seconds 5] [--container paycore-demo-mysql-1]

전제(Coordinator 준비 — 자세한 절차는 docs/repository/ACQUIRING-001-ACQUIRING.md §16 "E2E 실행 방법"):
  * Demo MySQL(FINANCIAL — 기본 컨테이너 paycore-demo-mysql-1)·Simulator MySQL, payment-api·institution-simulator·financial-batch가 Demo 설정으로 기동돼 있다.
  * financial-batch: paycore.financial.acquiring.transport.acquirers.<ACQ>(SFTP → Simulator), schedule.enabled=true(lane <ACQ>/TEST, first-business-date ≤ 업무일),
    results.deadline(Demo 예: PT3M).
  * Simulator: paycore.simulator.acquirer.acquirers[0].code=<ACQ>, van-client = PG client, 시나리오 — reject: APPROVEDAMOUNTMINOR=<amount> ACTION=REJECT
    REASON=DEMO_REJECTED, missing-result: APPROVEDAMOUNTMINOR=<amount> ACTION=DELAY_RESULT DELAY=(FINANCIAL results.deadline보다 긴 값, 예: PT6M).
  * 업무일 <date>에 매입사 <ACQ>로 승인된 Demo 결제(승인액 <amount>, TEST)가 payment-api → FINANCIAL relay로 들어와 있다(가상 VAN 원장에도 있어야 ACCEPTED가 난다).
비밀: DB 비밀번호는 환경변수 PAYCORE_FINANCIAL_DB_PASSWORD에서 `docker exec -e MYSQL_PWD`로 넘긴다(값은 argv·출력·로그에 없다). 이름만 검사한다.
종료 코드: 0 = 기대 상태 도달, 1 = 시간 안에 도달 못 함/어긋남(관찰 요약 출력), 2 = 전제 미충족(설정·승인 없음), 64 = 인자 오류.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from datetime import date

ACQUIRER = re.compile(r"^[A-Z0-9]{1,32}$")
CONTAINER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
SCENARIOS = ("normal", "reject", "missing-result")
DB_USER = "paycore_financial"
DB_NAME = "paycore_financial"
PASSWORD_ENV = "PAYCORE_FINANCIAL_DB_PASSWORD"


def parse_args(argv):
    p = argparse.ArgumentParser(prog="acquiring_e2e.py", description="ACQUIRING-001 FINANCIAL <-> Simulator E2E (read-only observer)")
    p.add_argument("--scenario", required=True, choices=SCENARIOS)
    p.add_argument("--acquirer", required=True)
    p.add_argument("--business-date", required=True)
    p.add_argument("--amount", required=True, type=int)
    p.add_argument("--timeout-seconds", type=int, default=900)
    p.add_argument("--poll-seconds", type=int, default=5)
    p.add_argument("--container", default="paycore-demo-mysql-1")
    a = p.parse_args(argv)
    if not ACQUIRER.match(a.acquirer):
        p.error("--acquirer must match [A-Z0-9]{1,32}")
    try:
        a.business_date = date.fromisoformat(a.business_date)
    except ValueError:
        p.error("--business-date must be yyyy-MM-dd")
    if not 1 <= a.amount <= 10**12:
        p.error("--amount must be a positive KRW integer")
    if not 10 <= a.timeout_seconds <= 7200 or not 1 <= a.poll_seconds <= 60:
        p.error("--timeout-seconds within 10..7200, --poll-seconds within 1..60")
    if not CONTAINER.match(a.container):
        p.error("--container must be a docker container name")
    return a


def file_id(acquirer, business_date):
    return f"ACQ.{acquirer}.TEST.{business_date.strftime('%Y%m%d')}.001"


def queries(acquirer, business_date, amount):
    """고정 SQL 템플릿(입력은 위 정규식·정수로 검증된 값만 들어간다). 결과는 탭 구분 한 줄씩."""
    fid = file_id(acquirer, business_date)
    day = business_date.isoformat()
    return {
        "candidates": (
            "SELECT COUNT(*) FROM payment_fact_authorization WHERE acquirer_code = '%s' AND environment = 'TEST' AND amount_minor = %d "
            "AND DATE(CONVERT_TZ(approved_at, '+00:00', '+09:00')) = '%s'" % (acquirer, amount, day)
        ),
        "file": "SELECT transmission_state, receipt_state, result_state, record_count FROM acquisition_file WHERE file_id = '%s'" % fid,
        "results": (
            "SELECT r.transaction_id, x.entry_no, x.result, COALESCE(x.reason_code, ''), x.source FROM acquisition_file_record r "
            "JOIN acquisition_record_result x ON x.file_id = r.file_id AND x.seq = r.seq WHERE r.file_id = '%s' AND r.record_amount_minor = %d "
            "ORDER BY r.transaction_id, x.entry_no" % (fid, amount)
        ),
        "responses": (
            "SELECT file_name, disposition, COALESCE(quarantine_reason, '') FROM acquisition_response WHERE file_id = '%s' ORDER BY id" % fid
        ),
    }


def mysql_argv(container, sql):
    """docker exec argv. 비밀번호는 `-e MYSQL_PWD`(값 없이 — 호출 환경에서 전달)로만 넘긴다: argv·프로세스 목록에 값이 없다."""
    return ["docker", "exec", "-e", "MYSQL_PWD", container, "mysql", "-h", "127.0.0.1", "-u", DB_USER, "-D", DB_NAME, "-N", "-B", "-e", sql]


def expectation_met(scenario, file_row, result_rows):
    """기대 상태. file_row = [transmission, receipt, result, record_count], result_rows = [[txn, entry_no, result, reason, source], …]."""
    if not file_row or file_row[0] != "TRANSMITTED":
        return False
    by_txn = {}
    for txn, _entry, result, reason, source in result_rows:
        by_txn.setdefault(txn, []).append((result, reason, source))
    if not by_txn:
        return False
    if scenario == "normal":
        return file_row[1] == "RECEIVED" and all(v == [("ACCEPTED", "", "RESULT_FILE")] for v in by_txn.values())
    if scenario == "reject":
        return file_row[1] == "RECEIVED" and all(len(v) == 1 and v[0][0] == "REJECTED" and v[0][1] == "DEMO_REJECTED" for v in by_txn.values())
    # missing-result: 결과 마감 UNKNOWN(보존) 뒤 늦은 ACCEPTED(D-ACQ-004·010).
    return all([r[0] for r in v] == ["UNKNOWN", "ACCEPTED"] and v[0][2] == "DEADLINE" for v in by_txn.values())


def run_sql(container, sql, env):
    out = subprocess.run(mysql_argv(container, sql), env=env, capture_output=True, text=True, timeout=60, check=False)
    if out.returncode != 0:
        # stderr에는 비밀이 없다(비밀번호는 환경변수). 그래도 첫 줄만 남긴다.
        raise RuntimeError("mysql query failed: " + (out.stderr.strip().splitlines() or ["(no stderr)"])[0][:200])
    return [line.split("\t") for line in out.stdout.splitlines() if line]


def main(argv):
    a = parse_args(argv)
    if not os.environ.get(PASSWORD_ENV):
        print(json.dumps({"result": "PRECONDITION", "missing": [PASSWORD_ENV]}))
        return 2
    env = dict(os.environ)
    env["MYSQL_PWD"] = env[PASSWORD_ENV]
    q = queries(a.acquirer, a.business_date, a.amount)
    try:
        candidates = int(run_sql(a.container, q["candidates"], env)[0][0])
    except (RuntimeError, IndexError, ValueError, subprocess.TimeoutExpired, FileNotFoundError) as e:
        print(json.dumps({"result": "PRECONDITION", "error": type(e).__name__}))
        return 2
    if candidates == 0:
        print(json.dumps({"result": "PRECONDITION", "error": "no TEST approval for the lane, business date and amount in FINANCIAL"}))
        return 2
    deadline = time.monotonic() + a.timeout_seconds
    observed = {}
    while time.monotonic() < deadline:
        try:
            file_rows = run_sql(a.container, q["file"], env)
            result_rows = run_sql(a.container, q["results"], env)
            responses = run_sql(a.container, q["responses"], env)
        except (RuntimeError, subprocess.TimeoutExpired) as e:
            observed = {"error": type(e).__name__}
            time.sleep(a.poll_seconds)
            continue
        observed = {"file": file_rows[0] if file_rows else None, "results": result_rows, "responses": responses}
        if expectation_met(a.scenario, observed["file"], result_rows):
            print(json.dumps({"result": "PASS", "scenario": a.scenario, "fileId": file_id(a.acquirer, a.business_date), "candidates": candidates, **observed}))
            return 0
        time.sleep(a.poll_seconds)
    print(json.dumps({"result": "FAIL", "scenario": a.scenario, "fileId": file_id(a.acquirer, a.business_date), "candidates": candidates, **observed}))
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
