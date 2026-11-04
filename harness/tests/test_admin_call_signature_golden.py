"""ADMIN → PAYMENT 내부 호출 서명 계약 v1 golden(ADMIN-PAYMENT-OPS-001 §4.2·§4.19 ②, D-OPS-001 — 두 앱 코드와 독립 생성).

PAYMENT(apps/payment-api `payment.adapter.web.admin` 서명 검증)와 ADMIN(apps/internal-admin `paymentops`·`merchantops` 서명 생성, S5), MASTER
(apps/master-api `merchant.adapter.web.admin` 서명 검증, MERCHANT-OPS-001 — 같은 계약, 다른 키 공간)는 코드를 공유하지 않고 이 파일 하나로 서명 계약을 맞춘다. 이 시험은 Python 표준 라이브러리(`hmac`·`hashlib`·`base64`)만으로 golden을 만들어 세 앱 시험 자원의 파일과 바이트 비교한다.

계약 요점:
- 헤더 `PayCore-Admin-Signature: t=<unix 초>,kid=<keyId>,v1=<64 소문자 hex>` — 전체가 정규식
  `^t=([0-9]{1,10}),kid=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})$`에 맞아야 한다(공백·순서 변경·추가 필드·대문자 hex 거부).
- canonical = "paycore.admin-call.v1\\n" + METHOD + "\\n" + 정규화 경로 + "\\n" + t(헤더의 숫자 그대로) + "\\n" + 소문자 hex(SHA-256(본문 바이트)).
  UTF-8, 끝 개행 없음, GET 본문은 빈 바이트. 정규화 경로는 query 없는 경로(context path 금지 — 요청 URI 경로와 같다).
- HMAC-SHA256 키 = 설정 비밀(Base64)을 디코드한 바이트(32바이트 이상). v1 = 소문자 hex(HMAC(키, canonical UTF-8)).
- 검증 순서: (본문 크기 16 KiB — 이 golden 밖) → query 문자열(있으면 400 QUERY_NOT_ALLOWED) → 헤더 문법(MALFORMED) → 시각 창 |now − t| ≤ 300초(Demo, 경계 포함,
  STALE) → kid(수신자가 모르면 UNKNOWN_KID) → 서명(상수 시간 비교, SIGNATURE). query 외 거절은 모두 401 `UNAUTHENTICATED`(원인 비구별).
- 같은 본문이라도 경로가 다르면 서명이 다르다(실행 POST ↔ 기한 확인 POST 교차 사용 불가 — D-OPS-009).
값은 Demo(운영 키·ID 아님). `python3 harness/tests/test_admin_call_signature_golden.py --write`로 다시 쓴다(검토된 계약 변경일 때만).
"""
import base64
import hashlib
import hmac
import json
import os
import re
import sys
import unittest
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
NAME = "admin-call-signature-v1.golden.json"
TARGETS = [os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", NAME) for app in ("internal-admin", "payment-api", "master-api")]

HEADER_NAME = "PayCore-Admin-Signature"
CANONICAL_TAG = "paycore.admin-call.v1"
WINDOW_SECONDS = 300
NOW = 1792500300
HEADER_RE = re.compile(r"t=([0-9]{1,10}),kid=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})")


def demo_secret(tag):
    """시험 전용 비밀(Base64, 디코드 36바이트 — PAYMENT 설정 문법 32바이트 이상)."""
    return base64.b64encode(("test-only-admin-call-golden-key-" + tag).encode("ascii")).decode("ascii")


KEYS = OrderedDict([("adm-golden-k1", demo_secret("0001")), ("adm-golden-k2", demo_secret("0002"))])

PAY = "pay_01JC0000000000000000000001"
APR = "apr_01JC0000000000000000000001"
PATH_TARGET = "/internal/v1/admin/payments/%s/operator-cancel-target" % PAY
PATH_EXECUTE = "/internal/v1/admin/operator-cancellations"
PATH_DECISION = "/internal/v1/admin/operator-cancellations/%s" % APR
PATH_LIST = "/internal/v1/admin/operator-decisions/after/0"
PATH_DEADLINE = "/internal/v1/admin/operator-cancellations/%s/deadline-confirmation" % APR

BODY_EXECUTE = ('{"approvalId":"%s","paymentId":"%s","amountMinor":7000,"currency":"KRW","requestedBy":"adm_01JC00000000000000000000R1",'
                '"approvedBy":"adm_01JC00000000000000000000A1","reason":"duplicate charge correction",'
                '"purpose":{"kind":"INCIDENT_OR_RECON_CORRECTION","reference":"RECON-2026-0001"},'
                '"approvedTargetFingerprint":"oct1:0000000000000000000000000000000000000000000000000000000000000000",'
                '"approvedAt":"2026-10-27T01:02:03.456Z","executeBefore":"2026-10-28T01:02:03.456Z","correlationId":"corr-0001"}') % (APR, PAY)
BODY_EXECUTE_HANGUL = BODY_EXECUTE.replace("duplicate charge correction", "중복 승인 보정 \U00020000")


def body_sha256(body):
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


def canonical(method, path, t, body):
    return "\n".join([CANONICAL_TAG, method, path, str(t), body_sha256(body)])


def v1(kid, method, path, t, body):
    key = base64.b64decode(KEYS[kid])
    return hmac.new(key, canonical(method, path, t, body).encode("utf-8"), hashlib.sha256).hexdigest()


def header(kid, method, path, t, body):
    return "t=%s,kid=%s,v1=%s" % (t, kid, v1(kid, method, path, t, body))


def verify(method, path, query, body, header_value, now, known_kids):
    """독립 검증 구현(PAYMENT 판정의 기준). 반환 (결과, 사유, HTTP 상태)."""
    if query is not None:
        return "REJECT", "QUERY_NOT_ALLOWED", 400
    m = HEADER_RE.fullmatch(header_value) if header_value is not None else None
    if m is None:
        return "REJECT", "MALFORMED", 401
    t, kid, presented = m.group(1), m.group(2), m.group(3)
    if abs(now - int(t)) > WINDOW_SECONDS:
        return "REJECT", "STALE", 401
    if kid not in known_kids:
        return "REJECT", "UNKNOWN_KID", 401
    key = base64.b64decode(KEYS[kid])
    expected = hmac.new(key, canonical(method, path, t, body).encode("utf-8"), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, presented):
        return "REJECT", "SIGNATURE", 401
    return "ACCEPT", None, None


def sign_vector(name, method, path, body, kid="adm-golden-k1", t=NOW):
    return OrderedDict([
        ("name", name), ("method", method), ("path", path), ("t", t), ("kid", kid), ("body", body),
        ("bodySha256", body_sha256(body)), ("canonical", canonical(method, path, t, body)), ("v1", v1(kid, method, path, t, body)),
        ("header", header(kid, method, path, t, body)),
    ])


def sign_vectors():
    return [
        sign_vector("target-get", "GET", PATH_TARGET, ""),
        sign_vector("execute-post", "POST", PATH_EXECUTE, BODY_EXECUTE),
        sign_vector("execute-post-hangul-reason", "POST", PATH_EXECUTE, BODY_EXECUTE_HANGUL),
        sign_vector("decision-get", "GET", PATH_DECISION, ""),
        sign_vector("decision-list-get", "GET", PATH_LIST, ""),
        sign_vector("deadline-confirmation-post", "POST", PATH_DEADLINE, BODY_EXECUTE),
        sign_vector("execute-post-second-key", "POST", PATH_EXECUTE, BODY_EXECUTE, kid="adm-golden-k2"),
    ]


ALL_KIDS = list(KEYS.keys())


def case(name, method, path, body, header_value, query=None, now=NOW, known=None):
    known = ALL_KIDS if known is None else known
    result, reason, status = verify(method, path, query, body, header_value, now, known)
    return OrderedDict([
        ("name", name), ("method", method), ("path", path), ("query", query), ("body", body), ("header", header_value), ("now", now),
        ("knownKids", known), ("expected", OrderedDict([("result", result), ("reason", reason), ("httpStatus", status)])),
    ])


def verify_cases():
    h_exec = header("adm-golden-k1", "POST", PATH_EXECUTE, NOW, BODY_EXECUTE)
    h_deadline = header("adm-golden-k1", "POST", PATH_DEADLINE, NOW, BODY_EXECUTE)
    h_get = header("adm-golden-k1", "GET", PATH_TARGET, NOW, "")
    good_v1 = v1("adm-golden-k1", "POST", PATH_EXECUTE, NOW, BODY_EXECUTE)
    return [
        case("accept-execute-post", "POST", PATH_EXECUTE, BODY_EXECUTE, h_exec),
        case("accept-target-get", "GET", PATH_TARGET, "", h_get),
        case("accept-window-minus-300", "POST", PATH_EXECUTE, BODY_EXECUTE, h_exec, now=NOW + 300),
        case("accept-window-plus-300", "POST", PATH_EXECUTE, BODY_EXECUTE, h_exec, now=NOW - 300),
        case("reject-window-minus-301", "POST", PATH_EXECUTE, BODY_EXECUTE, h_exec, now=NOW + 301),
        case("reject-window-plus-301", "POST", PATH_EXECUTE, BODY_EXECUTE, h_exec, now=NOW - 301),
        case("accept-second-key-while-rotating", "POST", PATH_EXECUTE, BODY_EXECUTE, header("adm-golden-k2", "POST", PATH_EXECUTE, NOW, BODY_EXECUTE)),
        case("reject-retired-second-key", "POST", PATH_EXECUTE, BODY_EXECUTE, header("adm-golden-k2", "POST", PATH_EXECUTE, NOW, BODY_EXECUTE),
             known=["adm-golden-k1"]),
        case("reject-unknown-kid", "POST", PATH_EXECUTE, BODY_EXECUTE, "t=%d,kid=adm-unknown,v1=%s" % (NOW, good_v1)),
        case("reject-kid-swapped-to-other-known-key", "POST", PATH_EXECUTE, BODY_EXECUTE, "t=%d,kid=adm-golden-k2,v1=%s" % (NOW, good_v1)),
        case("reject-body-changed", "POST", PATH_EXECUTE, BODY_EXECUTE.replace('"amountMinor":7000', '"amountMinor":7001'), h_exec),
        case("reject-path-changed", "POST", PATH_EXECUTE + "x", BODY_EXECUTE, h_exec),
        case("reject-method-changed", "PUT", PATH_EXECUTE, BODY_EXECUTE, h_exec),
        case("reject-t-changed", "POST", PATH_EXECUTE, BODY_EXECUTE, h_exec.replace("t=%d" % NOW, "t=%d" % (NOW + 1))),
        case("reject-execute-signature-on-deadline-path", "POST", PATH_DEADLINE, BODY_EXECUTE, h_exec),
        case("reject-deadline-signature-on-execute-path", "POST", PATH_EXECUTE, BODY_EXECUTE, h_deadline),
        case("reject-query-string-even-if-signed", "GET", PATH_TARGET, "", h_get, query="x=1"),
        case("reject-query-string-unsigned", "GET", PATH_TARGET, "", None, query="watermark=0"),
        case("reject-missing-header", "GET", PATH_TARGET, "", None),
        case("reject-empty-header", "GET", PATH_TARGET, "", ""),
        case("reject-uppercase-hex", "POST", PATH_EXECUTE, BODY_EXECUTE, "t=%d,kid=adm-golden-k1,v1=%s" % (NOW, good_v1.upper())),
        case("reject-spaces", "POST", PATH_EXECUTE, BODY_EXECUTE, h_exec.replace(",", ", ")),
        case("reject-field-order", "POST", PATH_EXECUTE, BODY_EXECUTE, "kid=adm-golden-k1,t=%d,v1=%s" % (NOW, good_v1)),
        case("reject-extra-field", "POST", PATH_EXECUTE, BODY_EXECUTE, h_exec + ",v2=abc"),
        case("reject-short-v1", "POST", PATH_EXECUTE, BODY_EXECUTE, "t=%d,kid=adm-golden-k1,v1=%s" % (NOW, good_v1[:63])),
        case("reject-t-eleven-digits", "POST", PATH_EXECUTE, BODY_EXECUTE, "t=0%d,kid=adm-golden-k1,v1=%s" % (NOW, good_v1)),
        case("reject-kid-too-long", "POST", PATH_EXECUTE, BODY_EXECUTE, "t=%d,kid=%s,v1=%s" % (NOW, "k" * 33, good_v1)),
        case("reject-kid-bad-character", "POST", PATH_EXECUTE, BODY_EXECUTE, "t=%d,kid=adm/k1,v1=%s" % (NOW, good_v1)),
    ]


def golden_text():
    golden = OrderedDict([
        ("description", "ADMIN-PAYMENT-OPS-001 ADMIN -> PAYMENT internal call signature v1 (generated independently of both apps with Python hmac/hashlib; "
                        "Demo keys and identifiers, not production values)."),
        ("header", HEADER_NAME),
        ("headerPattern", "^" + HEADER_RE.pattern + "$"),
        ("canonicalTag", CANONICAL_TAG),
        ("canonical", "tag LF METHOD LF normalizedPath LF t LF lowercase-hex(sha256(body)); UTF-8; no trailing LF; GET body is empty"),
        ("hmacKey", "base64-decoded secret bytes"),
        ("windowSeconds", WINDOW_SECONDS),
        ("checkOrder", ["QUERY_NOT_ALLOWED", "MALFORMED", "STALE", "UNKNOWN_KID", "SIGNATURE"]),
        ("keys", [OrderedDict([("kid", k), ("secretBase64", v)]) for k, v in KEYS.items()]),
        ("signVectors", sign_vectors()),
        ("verifyCases", verify_cases()),
    ])
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


class AdminCallSignatureGolden(unittest.TestCase):
    def test_golden_files_of_both_apps_equal_the_independent_generation(self):
        expected = golden_text()
        for path in TARGETS:
            with open(path, encoding="utf-8", newline="") as f:
                self.assertEqual(f.read(), expected, path)

    def test_demo_keys_are_at_least_32_bytes_and_distinct(self):
        decoded = [base64.b64decode(v) for v in KEYS.values()]
        self.assertTrue(all(len(k) >= 32 for k in decoded))
        self.assertEqual(len(set(decoded)), len(decoded))

    def test_execute_and_deadline_confirmation_signatures_differ_for_the_same_body(self):
        by = {v["name"]: v for v in sign_vectors()}
        self.assertEqual(by["execute-post"]["body"], by["deadline-confirmation-post"]["body"])
        self.assertNotEqual(by["execute-post"]["v1"], by["deadline-confirmation-post"]["v1"])
        cases = {c["name"]: c["expected"] for c in verify_cases()}
        self.assertEqual(cases["reject-execute-signature-on-deadline-path"]["reason"], "SIGNATURE")
        self.assertEqual(cases["reject-deadline-signature-on-execute-path"]["reason"], "SIGNATURE")

    def test_window_is_inclusive_at_300_seconds(self):
        cases = {c["name"]: c["expected"] for c in verify_cases()}
        self.assertEqual(cases["accept-window-minus-300"]["result"], "ACCEPT")
        self.assertEqual(cases["accept-window-plus-300"]["result"], "ACCEPT")
        self.assertEqual(cases["reject-window-minus-301"]["reason"], "STALE")
        self.assertEqual(cases["reject-window-plus-301"]["reason"], "STALE")

    def test_every_rejection_reason_and_the_query_rule_are_covered(self):
        reasons = {c["expected"]["reason"] for c in verify_cases()}
        self.assertEqual(reasons, {None, "QUERY_NOT_ALLOWED", "MALFORMED", "STALE", "UNKNOWN_KID", "SIGNATURE"})
        for c in verify_cases():
            if c["query"] is not None:
                self.assertEqual(c["expected"]["httpStatus"], 400)
            elif c["expected"]["result"] == "REJECT":
                self.assertEqual(c["expected"]["httpStatus"], 401)

    def test_sign_vectors_verify_with_the_independent_verifier(self):
        for v in sign_vectors():
            self.assertRegex(v["header"], "^" + HEADER_RE.pattern + "$")
            self.assertEqual(verify(v["method"], v["path"], None, v["body"], v["header"], NOW, ALL_KIDS)[0], "ACCEPT", v["name"])


if __name__ == "__main__":
    if "--write" in sys.argv:
        for target in TARGETS:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(golden_text())
    else:
        unittest.main()
