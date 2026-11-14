#!/usr/bin/env python3
"""MERCHANT-CODE-001 두 앱(ADMIN → MASTER) Demo E2E — MC-1-9 Demo 단계 + MC-2 코드 검색(GRADLE-CHECK 밖, Demo 스택에서 Coordinator가 실행).

원본: Coordinator가 2026-11-14 실제 Demo에서 10단계 PASS한 절차(~/gate-logs/mcode-mc1/demo/mc1-demo-e2e.py)를 저장소 harness로 옮겼다.
저장소 밖 모듈을 읽지 않는다(로그인·TOTP·세션은 이 파일 안에서 표준 라이브러리로 구현 — RFC 6238 SHA-1 6자리).

한 줄기(STEPS 순서 고정):
  ADMIN 로그인(비밀번호 + TOTP, 역할에 OPERATOR 단언) → 사업자 등록 → 코드 입력 가맹점 등록 성공 → 같은 submissionId 재전송 = replayed
  → 목록(businessId)·상세 200(코드·코드 이력 1건·변경 가능, 이력에 mer_ 없음) → [MC-2] ADMIN 코드 검색 = 1건(같은 가맹점, matchedRetiredCode=false, nextCursor=null)
  → [MC-2] 코드 + 다른 조건 = 400 → 같은 코드 새 submissionId = REJECTED MERCHANT_CODE_TAKEN(reference 없음) → 예약어 = MERCHANT_CODE_RESERVED
  → 형식 위반(대문자) = HTTP 400(이 E2E는 ADMIN 응답 상태만 본다 — MASTER 미호출 여부는 ADMIN 시험 MerchantRegistrationHttpSpec이 확인)
  → seed 목록의 모든 항목에 코드(mer_ 아님). 어느 단계도 503·결과 불명(UNKNOWN) 0건.
기록하는 HTTP 상태는 모두 실제 응답 값이다(로그인 포함).

사용(argv 고정 — 셸 문자열을 만들지 않는다):
  python3 -I harness/e2e/merchant_code_e2e.py --out <result.json> [--base-url http://127.0.0.1:8090] [--accounts-file ~/paycore-demo/admin-accounts.tsv]
      [--login demo-operator] [--timeout-seconds 30]
Demo 전용: --base-url은 http(s)://localhost | 127.0.0.1 | [::1] (+ 포트)만 받는다(경로·질의·사용자 정보 없음). 그 밖(운영·원격 endpoint)은 인자 오류 64.
  프록시를 쓰지 않고 redirect를 따라가지 않는다(세션 쿠키가 다른 곳으로 가지 않게).
계정 파일: Demo `admin-accounts.tsv`(login\\trole\\tpassword\\ttotpSecret). 기본 경로 = 환경변수 PAYCORE_DEMO_ADMIN_ACCOUNTS 또는 ~/paycore-demo/admin-accounts.tsv.
  비밀번호·TOTP 시크릿·세션 쿠키·CSRF 토큰은 출력·결과 파일·예외 메시지에 넣지 않는다.
종료 코드: 0 = PASS, 1 = FAIL(어긋난 단계 목록 출력) 또는 INCOMPLETE(로그인 뒤 예외 — 그때까지의 steps를 결과 파일에 쓴다),
  2 = 전제 미충족(계정 파일·로그인 실패·로그인 전 연결 불가 — 결과 파일 없음), 64 = 인자 오류.
"""
import argparse
import base64
import datetime
import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

STEPS = (
    "login",
    "register business",
    "register merchant with code",
    "resend same submissionId",
    "merchant list (businessId)",
    "merchant detail",
    "code search (merchantCode)",
    "code search with another criterion",
    "duplicate code new submissionId",
    "reserved word",
    "bad format uppercase",
    "seed merchant list",
)
LOOPBACK_NAMES = {"localhost"}
LOGIN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
MERCHANT_CODE = re.compile(r"^[a-z][a-z0-9]{5,13}$")
MERCHANT_ID = re.compile(r"^mer_[0-9A-HJKMNP-TV-Z]{26}$")
XSRF_COOKIE = "__Host-PCADMIN-XSRF"
ACCOUNTS_ENV = "PAYCORE_DEMO_ADMIN_ACCOUNTS"
DEFAULT_ACCOUNTS = os.path.join("~", "paycore-demo", "admin-accounts.tsv")


def loopback_base_url(value):
    """Demo 전용 base URL 검사. 통과하면 urlunsplit으로 다시 만든 정규 값(scheme://host[:port], 소문자 host, 끝 '/' 없음), 아니면 ValueError.
    공백·제어 문자는 거부한다(urlsplit은 탭·개행을 조용히 지운다 — 지운 뒤 통과시키지 않는다). IPv6 zone(`%`)·IPv4 매핑·축약 IPv4(127.1)·끝 점(localhost.)도 거부."""
    if any(c.isspace() or not c.isprintable() for c in value):
        raise ValueError("base url must not contain whitespace or control characters")
    parts = urllib.parse.urlsplit(value)
    if parts.scheme not in ("http", "https") or parts.username or parts.password or parts.query or parts.fragment or parts.path not in ("", "/"):
        raise ValueError("base url must be http(s)://<loopback>[:port] without path, query or user info")
    host = parts.hostname or ""
    try:
        port = parts.port  # 잘못된 포트는 ValueError
    except ValueError:
        raise ValueError("invalid port") from None
    if "%" in host:
        raise ValueError("base url host must be localhost, 127.0.0.1 or [::1]")
    if host not in LOOPBACK_NAMES:
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            raise ValueError("base url host must be localhost, 127.0.0.1 or [::1]") from None
        if address not in (ipaddress.ip_address("127.0.0.1"), ipaddress.ip_address("::1")):
            raise ValueError("base url host must be localhost, 127.0.0.1 or [::1]")
    netloc = ("[%s]" % host if ":" in host else host) + ("" if port is None else ":%d" % port)
    return urllib.parse.urlunsplit((parts.scheme, netloc, "", "", ""))


def parse_args(argv):
    p = argparse.ArgumentParser(prog="merchant_code_e2e.py", description="MERCHANT-CODE-001 ADMIN -> MASTER Demo E2E (MC-1-9 + MC-2 code search)")
    p.add_argument("--out", required=True)
    p.add_argument("--base-url", default="http://127.0.0.1:8090")
    p.add_argument("--accounts-file", default=os.environ.get(ACCOUNTS_ENV) or DEFAULT_ACCOUNTS)
    p.add_argument("--login", default="demo-operator")
    p.add_argument("--timeout-seconds", type=int, default=30)
    a = p.parse_args(argv)
    try:
        a.base_url = loopback_base_url(a.base_url)
    except ValueError as e:
        p.error("--base-url: " + str(e))
    if not LOGIN.match(a.login):
        p.error("--login must match [a-z0-9][a-z0-9._-]{0,63}")
    if not 1 <= a.timeout_seconds <= 120:
        p.error("--timeout-seconds within 1..120")
    a.accounts_file = os.path.expanduser(a.accounts_file)
    return a


def totp(secret_b32, at=None):
    """RFC 6238 TOTP(SHA-1, 30초, 6자리) — ADMIN TotpVerifier와 같은 규칙(golden: harness/tests/test_totp_rfc6238_golden.py)."""
    key = base64.b32decode(secret_b32.upper() + "=" * (-len(secret_b32) % 8))
    counter = int(time.time() if at is None else at) // 30
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return "%06d" % ((struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1000000)


class Account:
    """계정 한 줄. repr에 비밀 값이 없다."""

    def __init__(self, login, role, password, secret):
        self.login, self.role, self._password, self._secret = login, role, password, secret

    def __repr__(self):
        return "Account(login=%r, role=%r)" % (self.login, self.role)


def load_account(path, login):
    """TSV(login, role, password, totpSecret)에서 [login] 한 줄. 없으면 KeyError(값 없이)."""
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            fields = line.rstrip("\n").split("\t")
            if len(fields) == 4 and fields[0] == login:
                return Account(*fields)
    raise KeyError("login not found in accounts file")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401 — redirect 금지
        return None


def demo_opener():
    """프록시 없음·redirect 없음(3xx는 그대로 상태로 기록)."""
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())


class Session:
    """ADMIN 세션(쿠키·CSRF). call()은 상태와 해석한 본문을 돌려주고 마지막 상태를 [last_status]에 둔다 — 상태는 항상 실제 응답 값."""

    def __init__(self, base, timeout, opener=None):
        self.base, self.timeout, self.cookies, self.last_status = base, timeout, {}, None
        self.opener = opener or demo_opener()

    def call(self, path, method="GET", body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"Accept": "application/json", "Origin": self.base}
        if data is not None:
            headers["Content-Type"] = "application/json"
        if method not in ("GET", "HEAD") and XSRF_COOKIE in self.cookies:
            headers["X-XSRF-TOKEN"] = self.cookies[XSRF_COOKIE]
        if self.cookies:
            headers["Cookie"] = "; ".join("%s=%s" % kv for kv in self.cookies.items())
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            response = self.opener.open(request, timeout=self.timeout)
            status, raw, response_headers = response.status, response.read(), response.headers
        except urllib.error.HTTPError as e:
            status, raw, response_headers = e.code, e.read(), e.headers
        for cookie in response_headers.get_all("Set-Cookie") or []:
            name, _, rest = cookie.partition("=")
            value = rest.split(";", 1)[0]
            if value == "" or "Max-Age=0" in cookie:
                self.cookies.pop(name.strip(), None)
            else:
                self.cookies[name.strip()] = value
        self.last_status = status
        try:
            payload = json.loads(raw) if raw else None
        except ValueError:
            payload = None
        return status, payload


def brn():
    """Demo 사업자등록번호(체크섬이 맞는 무작위 값 — 실제 번호 아님)."""
    weights = [1, 3, 7, 1, 3, 7, 1, 3, 5]
    while True:
        digits = [int(c) for c in "%09d" % secrets.randbelow(10 ** 9)]
        if digits[0] == 0:
            continue
        total = sum(a * b for a, b in zip(digits, weights)) + (digits[8] * 5) // 10
        return "".join(map(str, digits)) + str((10 - total % 10) % 10)


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds")


# ---- 단계 판정(순수 함수 — harness 시험이 직접 부른다). 각 함수는 어긋남 목록을 돌려준다. 객체가 아닌 항목은 어긋남이다(예외로 끊기지 않는다). ----

def check_login(status, me):
    roles = me.get("roles") if isinstance(me, dict) else None
    problems = [] if status == 200 else ["login status %s" % status]
    if not isinstance(roles, list) or "OPERATOR" not in roles:
        problems.append("login roles lack OPERATOR")
    return problems


def page_items(name, page):
    """목록 응답의 항목. (객체 항목, 어긋남) — 응답이 객체가 아니거나 items가 목록이 아니거나 객체가 아닌 항목이 있으면 어긋남."""
    items = page.get("items") if isinstance(page, dict) else None
    if not isinstance(items, list):
        return [], ["%s: items is not a list" % name]
    objects = [i for i in items if isinstance(i, dict)]
    return objects, ([] if len(objects) == len(items) else ["%s: non-object item" % name])


def check_code_search(status, page, code, merchant_id):
    if status != 200:
        return ["code search status %s" % status]
    items, problems = page_items("code search", page)
    if problems:
        return problems
    if page.get("nextCursor", "missing") is not None:
        problems.append("code search nextCursor is not null")
    if len(items) != 1:
        problems.append("code search returned %d items (expected 1)" % len(items))
    else:
        item = items[0]
        if item.get("merchantCode") != code or item.get("merchantId") != merchant_id:
            problems.append("code search item is not the registered merchant")
        if item.get("matchedRetiredCode") is not False:
            problems.append("code search matchedRetiredCode is not false for a current code")
    return problems


def check_business_list(page, code):
    items, problems = page_items("merchant list", page)
    if not problems and not (len(items) == 1 and items[0].get("merchantCode") == code):
        problems.append("list item merchantCode != registered code")
    if any("matchedRetiredCode" in i for i in items):
        problems.append("list response carries matchedRetiredCode")
    return problems


def check_seed_list(page):
    items, problems = page_items("seed list", page)
    codes = [i.get("merchantCode") for i in items]
    if not items or not all(isinstance(c, str) and MERCHANT_CODE.match(c) for c in codes):
        problems.append("some list item lacks a §3.1 merchantCode")
    if any(isinstance(c, str) and "mer_" in c for c in codes):
        problems.append("list code contains mer_")
    return problems


def check_no_failure_signals(name, status, body):
    problems = []
    if status == 503:
        problems.append("%s: 503" % name)
    if isinstance(body, dict) and body.get("outcome") == "UNKNOWN":
        problems.append("%s: Unknown" % name)
    return problems


class Run:
    def __init__(self, session):
        self.session, self.steps, self.failures = session, [], []

    def step(self, name, path, method="GET", body=None, expect=(200,), record_body=True):
        assert name in STEPS, name
        status, payload = self.session.call(path, method, body)
        self.steps.append({"step": name, "at": now(), "method": method, "path": path, "status": status, "body": payload if record_body else None})
        if status not in expect:
            self.failures.append("%s: status %s not in %s" % (name, status, list(expect)))
        self.failures.extend(check_no_failure_signals(name, status, payload))
        return status, payload

    def check(self, condition, message):
        if not condition:
            self.failures.append(message)


def login(run, account):
    """비밀번호 + TOTP 로그인. 단계 기록에는 실제 상태와 역할만 남긴다(비밀·쿠키 없음)."""
    s = run.session
    statuses = []
    for path, method, body in (
        ("/api/auth/csrf", "GET", None),
        ("/api/auth/password", "POST", {"loginName": account.login, "password": account._password}),
        ("/api/auth/totp", "POST", {"code": totp(account._secret)}),
    ):
        status, payload = s.call(path, method, body)
        statuses.append(status)
        if status not in (200, 201, 204):
            run.steps.append({"step": "login", "at": now(), "status": status, "statuses": statuses, "body": None})
            return None
    me = payload
    csrf_status, _ = s.call("/api/auth/csrf")
    statuses.append(csrf_status)
    roles = me.get("roles") if isinstance(me, dict) else None
    run.steps.append({"step": "login", "at": now(), "status": statuses[2], "statuses": statuses, "body": {"roles": roles}})
    run.failures.extend(check_login(statuses[2], me))
    return me


def scenario(run, facts):
    """단계를 차례로 실행한다. [facts]에 알게 된 값을 즉시 적는다(중간 예외 때도 결과 파일에 남게)."""
    sid = lambda: uuid.uuid4().hex  # noqa: E731
    suffix = secrets.token_hex(3)
    code = "mce2e" + suffix  # 11자, §3.1 형식
    facts["code"] = code
    _, biz = run.step("register business", "/api/merchant-registrations/businesses", "POST",
                      {"submissionId": sid(), "registrationNumber": brn(), "displayName": "MC E2E 사업자 " + suffix})
    run.check(isinstance(biz, dict) and biz.get("outcome") == "SUCCEEDED", "business not SUCCEEDED")
    business_id = facts["businessId"] = biz.get("reference") if isinstance(biz, dict) else None

    body = {"submissionId": sid(), "businessId": business_id, "displayName": "MC E2E 가맹점 " + suffix, "merchantCode": code}
    _, mer = run.step("register merchant with code", "/api/merchant-registrations/merchants", "POST", body)
    run.check(isinstance(mer, dict) and mer.get("outcome") == "SUCCEEDED" and mer.get("replayed") is False, "merchant not SUCCEEDED")
    merchant_id = facts["merchantId"] = mer.get("reference") if isinstance(mer, dict) else None
    run.check(isinstance(merchant_id, str) and MERCHANT_ID.match(merchant_id) is not None, "merchant reference is not mer_<ULID>")

    _, rep = run.step("resend same submissionId", "/api/merchant-registrations/merchants", "POST", body)
    run.check(isinstance(rep, dict) and rep.get("outcome") == "SUCCEEDED" and rep.get("replayed") is True and rep.get("reference") == merchant_id, "resend not replayed")

    _, page = run.step("merchant list (businessId)", "/api/merchant-searches", "POST", {"businessId": business_id})
    run.failures.extend(check_business_list(page, code))

    _, det = run.step("merchant detail", "/api/merchants/%s" % urllib.parse.quote(merchant_id if isinstance(merchant_id, str) else "missing", safe=""))
    merchant = det.get("merchant") if isinstance(det, dict) else None
    merchant = merchant if isinstance(merchant, dict) else {}
    history = det.get("merchantCodeHistory") if isinstance(det, dict) else None
    run.check(merchant.get("merchantCode") == code, "detail merchant.merchantCode != code")
    run.check(isinstance(history, list) and len(history) == 1, "detail merchantCodeHistory not 1 entry")
    run.check(isinstance(det, dict) and det.get("merchantCodeChangeable") is True, "merchantCodeChangeable not true before contract approval")
    run.check("mer_" not in json.dumps(history or [], ensure_ascii=False), "code history exposes mer_")

    status, found = run.step("code search (merchantCode)", "/api/merchant-searches", "POST", {"merchantCode": code})
    run.failures.extend(check_code_search(status, found, code, merchant_id))

    run.step("code search with another criterion", "/api/merchant-searches", "POST", {"merchantCode": code, "lifecycle": "ACTIVE"}, expect=(400,))

    _, dup = run.step("duplicate code new submissionId", "/api/merchant-registrations/merchants", "POST",
                      {"submissionId": sid(), "businessId": business_id, "displayName": "MC E2E 중복 " + suffix, "merchantCode": code})
    run.check(isinstance(dup, dict) and dup.get("outcome") == "REJECTED" and dup.get("rejection") == "MERCHANT_CODE_TAKEN", "duplicate not MERCHANT_CODE_TAKEN")
    run.check(isinstance(dup, dict) and dup.get("reference") is None, "duplicate exposes reference")

    _, reserved = run.step("reserved word", "/api/merchant-registrations/merchants", "POST",
                           {"submissionId": sid(), "businessId": business_id, "displayName": "MC E2E 예약 " + suffix, "merchantCode": "admin1"})
    run.check(isinstance(reserved, dict) and reserved.get("outcome") == "REJECTED" and reserved.get("rejection") == "MERCHANT_CODE_RESERVED", "admin1 not MERCHANT_CODE_RESERVED")

    run.step("bad format uppercase", "/api/merchant-registrations/merchants", "POST",
             {"submissionId": sid(), "businessId": business_id, "displayName": "MC E2E 형식 " + suffix, "merchantCode": "MCE2E" + suffix.upper()}, expect=(400,))

    _, seed = run.step("seed merchant list", "/api/merchant-searches", "POST", {"limit": 50})
    run.failures.extend(check_seed_list(seed))
    seed_items, _ = page_items("seed list", seed)
    facts["seedCodes"] = sorted(str(i.get("merchantCode")) for i in seed_items)


def execute(run, account, out, base_url, started):
    """로그인 → 시나리오. 로그인 전 실패 = 전제 미충족(2, 결과 파일 없음). 로그인 뒤 예외(연결 끊김·예상 밖 응답 등)는 verdict INCOMPLETE —
    그때까지의 steps·failures를 결과 파일에 쓰고 1. 정상 종료면 PASS(0)/FAIL(1)."""
    try:
        me = login(run, account)
    except (urllib.error.URLError, OSError) as e:
        print(json.dumps({"result": "PRECONDITION", "error": type(e).__name__, "steps": len(run.steps)}))
        return 2
    if me is None:
        print(json.dumps({"result": "PRECONDITION", "error": "login failed", "steps": run.steps}))
        return 2
    facts = {}
    verdict = None
    try:
        scenario(run, facts)
    except (urllib.error.URLError, OSError, AttributeError, TypeError, KeyError, ValueError, IndexError) as e:
        run.failures.append("interrupted after %d steps: %s" % (len(run.steps), type(e).__name__))
        verdict = "INCOMPLETE"
    recorded = [s["step"] for s in run.steps]
    if verdict is None and recorded != list(STEPS):
        run.failures.append("step order %s != %s" % (recorded, list(STEPS)))
    verdict = verdict or ("PASS" if not run.failures else "FAIL")
    result = {"spec": "MERCHANT-CODE-001 MC-1-9 Demo + MC-2 code search (two apps)", "started": started, "ended": now(), "admin": base_url,
              **facts, "steps": run.steps, "failures": run.failures, "verdict": verdict}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(verdict, "failures=%d steps=%d" % (len(run.failures), len(run.steps)))
    for failure in run.failures:
        print(" -", failure)
    return 0 if verdict == "PASS" else 1


def main(argv):
    a = parse_args(argv)
    started = now()
    try:
        account = load_account(a.accounts_file, a.login)
    except (OSError, KeyError, UnicodeDecodeError) as e:
        print(json.dumps({"result": "PRECONDITION", "error": type(e).__name__, "accountsFile": a.accounts_file}))
        return 2
    return execute(Run(Session(a.base_url, a.timeout_seconds)), account, a.out, a.base_url, started)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
