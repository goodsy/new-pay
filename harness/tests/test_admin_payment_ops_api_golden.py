"""ADMIN -> PAYMENT 운영자 보정 취소 API 계약 golden v1(ADMIN-PAYMENT-OPS-001 §4.3·§4.5·§4.15·§4.19 ③, M-4 — 두 앱 코드와 독립 생성).

PAYMENT(apps/payment-api — 요청 해석·응답 본문)와 ADMIN(apps/internal-admin — 응답 → ADMIN 상태 매핑, S5)은 코드를 공유하지 않고 이 파일 하나로 API 계약을 맞춘다. 이 시험은 Python 표준
라이브러리(`json`)만으로 golden을 만들어 두 앱 시험 자원의 파일과 바이트 비교한다.

내용:
- requestExamples: 실행 POST 본문 예(정상·한글/보조 평면 사유·부분 금액) — PAYMENT 해석기가 받아야 한다(엄격 JSON, 모든 필드 필수).
- strictRejections: 엄격 JSON 거부 예(중복 key·뒤 토큰·문자열 금액·소수 금액·null·모르는 필드·빠진 필드) → 400 REQUEST_MALFORMED.
- responseCases: 응답 사례마다 {httpStatus, body, expectedAdminState, expectedIntegrityAlert}(§4.15 — 200 → EXECUTED, 업무 409·404 → OWNER_REJECTED, 409 CONFLICT·IDENTITY_MISMATCH →
  EXECUTION_UNKNOWN + 경보, 400 → OWNER_REJECTED + 경보, 401·503 → EXECUTION_UNKNOWN).
- decisionResponses·deadlineResponses·listPage: 결정 조회 3종(공통 필드 L-b), 기한 확인 4종(confirmed), 결정 목록 한 페이지.
- 실행마다 달라지는 값은 {"$placeholder": NAME}이고 placeholders에 정규식이 있다. PAYMENT는 실제 값이 정규식에 맞는지, ADMIN은 고정 예시 값으로 채워 재생한다.
값은 Demo(운영 ID 아님). `python3 harness/tests/test_admin_payment_ops_api_golden.py --write`로 다시 쓴다(검토된 계약 변경일 때만).
"""
import json
import os
import sys
import unittest
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
NAME = "admin-payment-ops-api-v1.golden.json"
TARGETS = [os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", NAME) for app in ("internal-admin", "payment-api")]

APR = "apr_01JC0000000000000000000001"
PAY = "pay_01JC0000000000000000000001"
REQ = "adm_01JC00000000000000000000R1"
APV = "adm_01JC00000000000000000000A1"
OCT1 = "oct1:" + "0" * 64
OPD1 = "opd1:" + "1" * 64

PLACEHOLDERS = OrderedDict([
    ("CANCELLATION_ID", "^pcn_[0-9A-HJKMNP-TV-Z]{26}$"),
    ("TIMESTAMP", "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$"),
    ("SEQ", "^[1-9][0-9]*$"),
    ("WATERMARK", "^(0|[1-9][0-9]*)$"),
    ("CONTENT_HASH", "^opd1:[0-9a-f]{64}$"),
])

BUSINESS_CODES = ["OPERATOR_TARGET_STALE", "CANCEL_IN_PROGRESS", "CURRENCY_MISMATCH", "ALREADY_FULLY_CANCELLED", "AMOUNT_EXCEEDS_BALANCE",
                  "PARTIAL_CANCEL_NOT_SUPPORTED", "PAYMENT_NOT_CANCELLABLE", "OPERATOR_SAME_PERSON", "OPERATOR_REASON_MISSING", "OPERATOR_REASON_INVALID",
                  "OPERATOR_BASIS_MISSING", "OPERATOR_BASIS_INVALID", "APPROVAL_EXECUTION_EXPIRED", "APPROVAL_WINDOW_INVALID"]
DERIVED_CODES = ["APPROVAL_CONFLICT", "APPROVAL_IDENTITY_MISMATCH"]
MALFORMED_CODES = ["REQUEST_MALFORMED", "APPROVAL_ID_MALFORMED", "PAYMENT_ID_MALFORMED", "OPERATOR_ID_MALFORMED"]
UNAVAILABLE_CODES = ["TEMPORARILY_UNAVAILABLE", "OUTCOME_UNKNOWN", "ADMIN_COMMAND_BUSY"]


def ph(name):
    return OrderedDict([("$placeholder", name)])


def request(amount=7000, reason="duplicate charge correction", reference="RECON-2026-0001", kind="INCIDENT_OR_RECON_CORRECTION"):
    body = OrderedDict([
        ("approvalId", APR), ("paymentId", PAY), ("amountMinor", amount), ("currency", "KRW"), ("requestedBy", REQ), ("approvedBy", APV), ("reason", reason),
        ("purpose", OrderedDict([("kind", kind), ("reference", reference)])), ("approvedTargetFingerprint", OCT1),
        ("approvedAt", "2026-10-27T01:02:03.456Z"), ("executeBefore", "2026-10-28T01:02:03.456Z"), ("correlationId", "corr-0001"),
    ])
    return json.dumps(body, ensure_ascii=False, separators=(",", ":"))


NORMAL = request()


def request_examples():
    return [
        OrderedDict([("name", "normal"), ("body", NORMAL)]),
        OrderedDict([("name", "hangul-and-supplementary-reason"), ("body", request(reason="중복 승인 보정\n두 번째 줄 \U00020000", reference="대사-2026 #7"))]),
        OrderedDict([("name", "partial-amount-terminated-merchant"), ("body", request(amount=1, kind="MERCHANT_TERMINATED_OBLIGATION", reference="TERM-2026-0007"))]),
    ]


def strict_rejections():
    amount = '"amountMinor":7000'
    cases = [
        ("duplicate-key", NORMAL.replace('{"approvalId"', '{"amountMinor":1,"approvalId"', 1)),
        ("trailing-token", NORMAL + " {}"),
        ("string-amount", NORMAL.replace(amount, '"amountMinor":"7000"')),
        ("decimal-amount", NORMAL.replace(amount, '"amountMinor":7000.0')),
        ("null-amount", NORMAL.replace(amount, '"amountMinor":null')),
        ("unknown-field", NORMAL.replace('{"approvalId"', '{"extra":1,"approvalId"', 1)),
        ("missing-field", NORMAL.replace(amount + ",", "")),
        ("reference-leading-space", NORMAL.replace('"reference":"RECON-2026-0001"', '"reference":" RECON-2026-0001"')),
        ("timestamp-without-millis", NORMAL.replace('"approvedAt":"2026-10-27T01:02:03.456Z"', '"approvedAt":"2026-10-27T01:02:03Z"')),
    ]
    return [OrderedDict([("name", n), ("body", b), ("httpStatus", 400), ("error", "REQUEST_MALFORMED")]) for n, b in cases]


def case(name, status, body, state, alert):
    return OrderedDict([("name", name), ("httpStatus", status), ("body", body), ("expectedAdminState", state), ("expectedIntegrityAlert", alert)])


def err(code):
    return OrderedDict([("error", code)])


def response_cases():
    out = [
        case("accepted-new", 200, OrderedDict([("cancellationId", ph("CANCELLATION_ID")), ("status", "IN_PROGRESS"), ("existing", False)]), "EXECUTED", False),
        case("accepted-replay", 200, OrderedDict([("cancellationId", ph("CANCELLATION_ID")), ("status", "COMPLETED"), ("existing", True)]), "EXECUTED", False),
    ]
    out += [case("rejected-" + c.lower(), 409, err(c), "OWNER_REJECTED", False) for c in BUSINESS_CODES]
    out += [case("derived-" + c.lower(), 409, err(c), "EXECUTION_UNKNOWN", True) for c in DERIVED_CODES]
    out.append(case("not-found", 404, err("PAYMENT_NOT_FOUND"), "OWNER_REJECTED", False))
    out += [case("malformed-" + c.lower(), 400, err(c), "OWNER_REJECTED", True) for c in MALFORMED_CODES]
    out.append(case("unauthenticated", 401, err("UNAUTHENTICATED"), "EXECUTION_UNKNOWN", False))
    out += [case("unavailable-" + c.lower(), 503, err(c), "EXECUTION_UNKNOWN", False) for c in UNAVAILABLE_CODES]
    return out


def common(decision):
    d = OrderedDict([("decision", decision)])
    if decision == "STARTED":
        d["cancellationId"] = ph("CANCELLATION_ID")
        d["cancellationStatus"] = "COMPLETED"
    else:
        d["code"] = "OPERATOR_TARGET_STALE"
    d.update([("paymentId", PAY), ("amountMinor", 7000), ("currency", "KRW"), ("requestedBy", REQ), ("approvedBy", APV), ("approvedTargetFingerprint", OCT1),
              ("contentHash", ph("CONTENT_HASH")), ("decidedAt", ph("TIMESTAMP")), ("legacy", False)])
    return d


def decision_responses():
    return [
        OrderedDict([("name", "started"), ("httpStatus", 200), ("body", common("STARTED")), ("expectedAdminState", "EXECUTED")]),
        OrderedDict([("name", "rejected"), ("httpStatus", 200), ("body", common("REJECTED")), ("expectedAdminState", "OWNER_REJECTED")]),
        OrderedDict([("name", "none"), ("httpStatus", 200), ("body", OrderedDict([("decision", "NONE")])), ("expectedAdminState", "UNCHANGED")]),
    ]


def deadline_responses():
    expired = common("REJECTED")
    expired["code"] = "APPROVAL_EXECUTION_EXPIRED"
    replay = common("REJECTED")
    return [
        OrderedDict([("name", "expired-confirmed"), ("body", OrderedDict(list(expired.items()) + [("confirmed", True)])), ("expectedAdminState", "OWNER_REJECTED"),
                     ("releasesPaymentBlock", True)]),
        OrderedDict([("name", "none-before-deadline"), ("body", OrderedDict([("decision", "NONE"), ("confirmed", False)])), ("expectedAdminState", "UNCHANGED"),
                     ("releasesPaymentBlock", False)]),
        OrderedDict([("name", "existing-started"), ("body", OrderedDict(list(common("STARTED").items()) + [("confirmed", False)])), ("expectedAdminState", "EXECUTED"),
                     ("releasesPaymentBlock", True)]),
        OrderedDict([("name", "existing-rejected"), ("body", OrderedDict(list(replay.items()) + [("confirmed", False)])), ("expectedAdminState", "OWNER_REJECTED"),
                     ("releasesPaymentBlock", True)]),
    ]


def list_page():
    item = OrderedDict([("seq", ph("SEQ")), ("approvalId", APR), ("paymentId", PAY), ("decision", "REJECTED"), ("code", "PAYMENT_NOT_FOUND"), ("amountMinor", 7000),
                        ("currency", "KRW"), ("requestedBy", REQ), ("approvedBy", APV), ("approvedTargetFingerprint", OCT1), ("contentHash", ph("CONTENT_HASH")),
                        ("decidedAt", ph("TIMESTAMP")), ("recordedAt", ph("TIMESTAMP")), ("origin", "EXECUTION_COMMAND")])
    return OrderedDict([("items", [item]), ("nextWatermark", ph("WATERMARK")), ("serverTime", ph("TIMESTAMP"))])


def golden_text():
    golden = OrderedDict([
        ("description", "ADMIN-PAYMENT-OPS-001 ADMIN -> PAYMENT operator correction cancel API contract v1 (generated independently of both apps; Demo identifiers)."),
        ("placeholders", PLACEHOLDERS),
        ("requestExamples", request_examples()),
        ("strictRejections", strict_rejections()),
        ("responseCases", response_cases()),
        ("decisionResponses", decision_responses()),
        ("deadlineResponses", deadline_responses()),
        ("listPage", list_page()),
    ])
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


class AdminPaymentOpsApiGolden(unittest.TestCase):
    def test_golden_files_of_both_apps_equal_the_independent_generation(self):
        expected = golden_text()
        for path in TARGETS:
            with open(path, encoding="utf-8", newline="") as f:
                self.assertEqual(f.read(), expected, path)

    def test_every_response_case_maps_to_an_admin_state_and_only_derived_or_malformed_raise_an_alert(self):
        for c in response_cases():
            self.assertIn(c["expectedAdminState"], {"EXECUTED", "OWNER_REJECTED", "EXECUTION_UNKNOWN"})
            code = c["body"].get("error")
            self.assertEqual(c["expectedIntegrityAlert"], code in DERIVED_CODES or code in MALFORMED_CODES, c["name"])

    def test_409_and_404_are_decisions_except_the_derived_codes(self):
        for c in response_cases():
            if c["httpStatus"] in (409, 404) and c["body"]["error"] not in DERIVED_CODES:
                self.assertEqual(c["expectedAdminState"], "OWNER_REJECTED")
            if c["httpStatus"] in (401, 503):
                self.assertEqual(c["expectedAdminState"], "EXECUTION_UNKNOWN")

    def test_request_examples_are_strict_json_with_all_fields(self):
        for e in request_examples():
            body = json.loads(e["body"])
            self.assertEqual(len(body), 12)
            self.assertIsInstance(body["amountMinor"], int)

    def test_no_reason_or_reference_field_in_any_response(self):
        def keys(node):
            if isinstance(node, dict):
                for k, v in node.items():
                    yield k
                    yield from keys(v)
            elif isinstance(node, list):
                for v in node:
                    yield from keys(v)
        found = set(keys([[c["body"] for c in response_cases()], [d["body"] for d in decision_responses()], [d["body"] for d in deadline_responses()], list_page()]))
        self.assertFalse(found & {"reason", "purpose", "reference"}, found)


if __name__ == "__main__":
    if "--write" in sys.argv:
        for target in TARGETS:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(golden_text())
    else:
        unittest.main()
