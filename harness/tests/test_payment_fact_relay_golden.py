"""PAYMENT → FINANCIAL 결제 사실 채널 계약 v1 golden(FIN-FACT-RELAY-001 §4.1, D-ACQ-001 — 두 앱 코드와 독립 생성).

PAYMENT(apps/payment-api `payment.adapter.integration` 봉투·서명 생성, ACK 해석)와 FINANCIAL(apps/financial-batch `paymentfact.adapter.web` 서명 검증·봉투 해석·ACK 생성)은
코드를 공유하지 않고 이 파일 하나로 계약을 맞춘다. 이 시험은 Python 표준 라이브러리(`hmac`·`hashlib`·`base64`·`json`)만으로 golden을 만들어 두 앱 시험 자원의 파일과
바이트 비교한다.

계약 요점:
- 본문 = 봉투 JSON(UTF-8, 공백 없는 compact, 필드 순서 고정): envelopeVersion(1)·eventId·effectKey·eventType·schemaVersion·eventCategory(FINANCIAL_FACT)·producer·
  aggregateType·aggregateId·aggregateVersion·occurredAt(UTC ms `…T01:02:03.456Z`)·payload. payload는 PAYMENT Outbox JSON 열을 읽은 문자열(MySQL 정규화 형식 —
  키 순서 = 길이·바이트 순, `", "`·`": "` 구분자)을 해석해 **키 순서를 유지한 채** compact로 다시 쓴 것이다. 같은 Outbox 행은 언제 보내도 같은 바이트다(재전송 동일성).
- 헤더 `PayCore-Fact-Signature: t=<unix 초>,kid=<keyId>,v1=<64 소문자 hex>` — 전체가 정규식에 맞아야 한다(공백·순서 변경·추가 필드·대문자 hex 거부).
- canonical = "paycore.payment-fact.v1\\nPOST\\n/internal/v1/payment-facts\\n" + t(헤더 숫자 그대로) + "\\n" + 소문자 hex(SHA-256(본문)). 끝 개행 없음.
- HMAC-SHA256 키 = 설정 비밀(Base64)을 디코드한 바이트(32바이트 이상). 검증 순서: 문법(MALFORMED) → 시각 창 |now − t| ≤ 300초(Demo, 경계 포함, STALE) →
  kid(UNKNOWN_KID) → 서명(SIGNATURE). 거절은 모두 401 UNAUTHENTICATED(원인 비구별).
- ACK(200 본문): consumer·eventId·effectKey·outcome(APPLIED·HELD·UNSUPPORTED·DUPLICATE)·DUPLICATE일 때만 originalOutcome(APPLIED·HELD·UNSUPPORTED).
  PAYMENT는 보낸 eventId·effectKey와 같고 consumer가 financial-batch일 때만 전달 완료로 본다(아니면 재시도).
- HTTP 분류(PAYMENT): 2xx = ACK 해석. 400·409·422는 본문 JSON `error`가 그 상태의 FINANCIAL 계약 코드(rejectCodes)일 때만 내용 거절(격리 — 같은 내용은 다시
  보내도 거절, 검토 m-1: 프록시·다른 서버의 4xx로 격리하지 않는다). 그 밖(코드 없는 4xx, 401·404·411·413·415·429·3xx·5xx·연결 실패) = 재시도.
값은 Demo(운영 키·ID 아님). `python3 harness/tests/test_payment_fact_relay_golden.py --write`로 다시 쓴다(검토된 계약 변경일 때만).
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
NAME = "payment-fact-relay-v1.golden.json"
TARGETS = [os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", NAME) for app in ("payment-api", "financial-batch")]

HEADER_NAME = "PayCore-Fact-Signature"
CANONICAL_TAG = "paycore.payment-fact.v1"
METHOD = "POST"
PATH = "/internal/v1/payment-facts"
WINDOW_SECONDS = 300
NOW = 1792500300
HEADER_RE = re.compile(r"t=([0-9]{1,10}),kid=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})")
CONSUMER = "financial-batch"


def demo_secret(tag):
    """시험 전용 비밀(Base64, 디코드 38바이트 — 설정 문법 32바이트 이상)."""
    return base64.b64encode(("test-only-payment-fact-golden-key-" + tag).encode("ascii")).decode("ascii")


KEYS = OrderedDict([("fact-golden-k1", demo_secret("0001")), ("fact-golden-k2", demo_secret("0002"))])
ALL_KIDS = list(KEYS.keys())


def mysql_json_text(value):
    """MySQL JSON 열을 CAST(… AS CHAR)로 읽은 모양: 객체 키는 (UTF-8 길이, 바이트) 순, 구분자 ', '·': '."""
    def norm(v):
        if isinstance(v, dict):
            return OrderedDict((k, norm(v[k])) for k in sorted(v.keys(), key=lambda k: (len(k.encode("utf-8")), k.encode("utf-8"))))
        if isinstance(v, list):
            return [norm(x) for x in v]
        return v
    return json.dumps(norm(value), ensure_ascii=False, separators=(", ", ": "))


AUTHORIZED_PAYLOAD = OrderedDict([
    ("environment", "TEST"), ("merchantAccountId", "demo-mid-001"), ("merchantSiteId", "demo-site-001"), ("merchantOrderId", "order-20261105-0001"),
    ("paymentId", "pay_01JC0000000000000000000001"), ("authorizationId", "pau_01JC0000000000000000000001"),
    ("transactionId", "ptx_01JC0000000000000000000001"), ("amount", 10000), ("currency", "KRW"), ("plan", OrderedDict([("kind", "LUMP_SUM")])),
    ("cardType", "CREDIT"), ("issuerCode", "SIMISSA"), ("acquirerCode", "SIMACQA"), ("approvalNumber", "30000001"), ("providerTransactionId", "SIMVAN-T-0001"),
    ("institutionTransactionDate", "2026-11-05"), ("institutionBusinessDate", None), ("approvedAt", "2026-11-05T01:02:03.456Z"),
    ("policyAssignmentVersion", "pa:demo-policy-1"), ("entitlementVersion", "es1:demo-merchant-001:3"),
])

CANCELLED_PAYLOAD = OrderedDict([
    ("environment", "TEST"), ("merchantAccountId", "demo-mid-001"), ("merchantSiteId", "사이트-001"), ("merchantOrderId", "order-20261105-0002"),
    ("paymentId", "pay_01JC0000000000000000000002"), ("authorizationId", "pau_01JC0000000000000000000002"),
    ("originalTransactionId", "ptx_01JC0000000000000000000002"), ("cancellationId", "pcn_01JC0000000000000000000001"),
    ("transactionId", "ptx_01JC0000000000000000000003"), ("amount", 3000), ("currency", "KRW"), ("scope", "PARTIAL"), ("authorizedAmount", 10000),
    ("returnSequence", 1), ("cumulativeReturnedAmount", 3000), ("returnStatusAfter", "PARTIALLY_RETURNED"), ("cancelApprovalNumber", "40000001"),
    ("providerTransactionId", "SIMVAN-C-0001"), ("originalProviderTransactionId", "SIMVAN-T-0002"), ("institutionTransactionDate", "2026-11-05"),
    ("institutionBusinessDate", "2026-11-05"), ("cancelledAt", "2026-11-05T02:03:04.567Z"), ("policyAssignmentVersion", "pa:demo-policy-1"),
    ("entitlementVersion", "es1:demo-merchant-001:3"), ("initiatorKind", "MERCHANT"), ("originalPlan", OrderedDict([("kind", "INSTALLMENT"), ("months", 3)])),
    ("originalCardType", "CREDIT"),
])

UNSUPPORTED_PAYLOAD = OrderedDict([("paymentId", "pay_01JC0000000000000000000003"), ("note", "future schema")])

ENVELOPE_INPUTS = [
    OrderedDict([
        ("name", "authorized"), ("eventId", "0b8a6f4e-6a3c-4c8e-9f51-2d3f1c000001"), ("effectKey", "PaymentAuthorized:ptx_01JC0000000000000000000001"),
        ("eventType", "PaymentAuthorized"), ("schemaVersion", 1), ("eventCategory", "FINANCIAL_FACT"), ("producer", "payment-api/payment"),
        ("aggregateType", "PaymentAuthorization"), ("aggregateId", "pau_01JC0000000000000000000001"), ("aggregateVersion", 2),
        ("occurredAt", "2026-11-05T01:02:03.456Z"), ("payloadJson", mysql_json_text(AUTHORIZED_PAYLOAD)),
    ]),
    OrderedDict([
        ("name", "cancelled-partial-hangul-site"), ("eventId", "0b8a6f4e-6a3c-4c8e-9f51-2d3f1c000002"), ("effectKey", "PaymentCancelled:ptx_01JC0000000000000000000003"),
        ("eventType", "PaymentCancelled"), ("schemaVersion", 1), ("eventCategory", "FINANCIAL_FACT"), ("producer", "payment-api/payment"),
        ("aggregateType", "PaymentCancellation"), ("aggregateId", "pcn_01JC0000000000000000000001"), ("aggregateVersion", 4),
        ("occurredAt", "2026-11-05T02:03:04.567Z"), ("payloadJson", mysql_json_text(CANCELLED_PAYLOAD)),
    ]),
    OrderedDict([
        ("name", "unsupported-schema-version"), ("eventId", "0b8a6f4e-6a3c-4c8e-9f51-2d3f1c000003"), ("effectKey", "PaymentAuthorized:ptx_01JC0000000000000000000009"),
        ("eventType", "PaymentAuthorized"), ("schemaVersion", 2), ("eventCategory", "FINANCIAL_FACT"), ("producer", "payment-api/payment"),
        ("aggregateType", "PaymentAuthorization"), ("aggregateId", "pau_01JC0000000000000000000009"), ("aggregateVersion", 1),
        ("occurredAt", "2026-11-05T03:04:05.678Z"), ("payloadJson", mysql_json_text(UNSUPPORTED_PAYLOAD)),
    ]),
]


def body(inp):
    """봉투 본문(compact, 필드 순서 고정, payload는 읽은 문자열의 키 순서 유지)."""
    envelope = OrderedDict([
        ("envelopeVersion", 1), ("eventId", inp["eventId"]), ("effectKey", inp["effectKey"]), ("eventType", inp["eventType"]),
        ("schemaVersion", inp["schemaVersion"]), ("eventCategory", inp["eventCategory"]), ("producer", inp["producer"]), ("aggregateType", inp["aggregateType"]),
        ("aggregateId", inp["aggregateId"]), ("aggregateVersion", inp["aggregateVersion"]), ("occurredAt", inp["occurredAt"]),
        ("payload", json.loads(inp["payloadJson"], object_pairs_hook=OrderedDict)),
    ])
    return json.dumps(envelope, ensure_ascii=False, separators=(",", ":"))


def body_sha256(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def canonical(t, text):
    return "\n".join([CANONICAL_TAG, METHOD, PATH, str(t), body_sha256(text)])


def v1(kid, t, text):
    return hmac.new(base64.b64decode(KEYS[kid]), canonical(t, text).encode("utf-8"), hashlib.sha256).hexdigest()


def header(kid, t, text):
    return "t=%s,kid=%s,v1=%s" % (t, kid, v1(kid, t, text))


def verify(text, header_value, now, known_kids):
    """독립 검증 구현(FINANCIAL 판정의 기준). 반환 (결과, 사유, HTTP 상태)."""
    m = HEADER_RE.fullmatch(header_value) if header_value is not None else None
    if m is None:
        return "REJECT", "MALFORMED", 401
    t, kid, presented = m.group(1), m.group(2), m.group(3)
    if abs(now - int(t)) > WINDOW_SECONDS:
        return "REJECT", "STALE", 401
    if kid not in known_kids:
        return "REJECT", "UNKNOWN_KID", 401
    expected = hmac.new(base64.b64decode(KEYS[kid]), canonical(t, text).encode("utf-8"), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, presented):
        return "REJECT", "SIGNATURE", 401
    return "ACCEPT", None, None


def envelope_vectors():
    out = []
    for inp in ENVELOPE_INPUTS:
        text = body(inp)
        out.append(OrderedDict([
            ("input", inp), ("body", text), ("bodySha256", body_sha256(text)), ("t", NOW), ("kid", "fact-golden-k1"),
            ("canonical", canonical(NOW, text)), ("header", header("fact-golden-k1", NOW, text)),
        ]))
    return out


def case(name, text, header_value, now=NOW, known=None):
    known = ALL_KIDS if known is None else known
    result, reason, status = verify(text, header_value, now, known)
    return OrderedDict([
        ("name", name), ("body", text), ("header", header_value), ("now", now), ("knownKids", known),
        ("expected", OrderedDict([("result", result), ("reason", reason), ("httpStatus", status)])),
    ])


def verify_cases():
    b = body(ENVELOPE_INPUTS[0])
    h = header("fact-golden-k1", NOW, b)
    good = v1("fact-golden-k1", NOW, b)
    return [
        case("accept", b, h),
        case("accept-window-minus-300", b, h, now=NOW + 300),
        case("accept-window-plus-300", b, h, now=NOW - 300),
        case("reject-window-minus-301", b, h, now=NOW + 301),
        case("reject-window-plus-301", b, h, now=NOW - 301),
        case("accept-second-key-while-rotating", b, header("fact-golden-k2", NOW, b)),
        case("reject-retired-second-key", b, header("fact-golden-k2", NOW, b), known=["fact-golden-k1"]),
        case("reject-unknown-kid", b, "t=%d,kid=fact-unknown,v1=%s" % (NOW, good)),
        case("reject-kid-swapped-to-other-known-key", b, "t=%d,kid=fact-golden-k2,v1=%s" % (NOW, good)),
        case("reject-body-changed", b.replace('"amount":10000', '"amount":10001'), h),
        case("reject-t-changed", b, h.replace("t=%d" % NOW, "t=%d" % (NOW + 1))),
        case("reject-missing-header", b, None),
        case("reject-empty-header", b, ""),
        case("reject-uppercase-hex", b, "t=%d,kid=fact-golden-k1,v1=%s" % (NOW, good.upper())),
        case("reject-spaces", b, h.replace(",", ", ")),
        case("reject-field-order", b, "kid=fact-golden-k1,t=%d,v1=%s" % (NOW, good)),
        case("reject-extra-field", b, h + ",v2=abc"),
        case("reject-short-v1", b, "t=%d,kid=fact-golden-k1,v1=%s" % (NOW, good[:63])),
        case("reject-t-eleven-digits", b, "t=0%d,kid=fact-golden-k1,v1=%s" % (NOW, good)),
        case("reject-kid-too-long", b, "t=%d,kid=%s,v1=%s" % (NOW, "k" * 33, good)),
    ]


def ack(event_id, effect_key, outcome, original=None, consumer=CONSUMER):
    a = OrderedDict()
    if consumer is not None:
        a["consumer"] = consumer
    a["eventId"] = event_id
    a["effectKey"] = effect_key
    a["outcome"] = outcome
    if original is not None:
        a["originalOutcome"] = original
    return json.dumps(a, separators=(",", ":"))


def ack_cases():
    e, k = ENVELOPE_INPUTS[0]["eventId"], ENVELOPE_INPUTS[0]["effectKey"]
    other = "0b8a6f4e-6a3c-4c8e-9f51-2d3f1c0000ff"

    def c(name, text, expected, outcome=None, original=None):
        return OrderedDict([("name", name), ("sentEventId", e), ("sentEffectKey", k), ("body", text),
                            ("expected", OrderedDict([("result", expected), ("outcome", outcome), ("originalOutcome", original)]))])
    return [
        c("applied", ack(e, k, "APPLIED"), "ACKNOWLEDGED", "APPLIED"),
        c("held", ack(e, k, "HELD"), "ACKNOWLEDGED", "HELD"),
        c("unsupported", ack(e, k, "UNSUPPORTED"), "ACKNOWLEDGED", "UNSUPPORTED"),
        c("duplicate-of-held", ack(e, k, "DUPLICATE", "HELD"), "ACKNOWLEDGED", "DUPLICATE", "HELD"),
        c("duplicate-without-original", ack(e, k, "DUPLICATE"), "UNREADABLE"),
        c("original-on-non-duplicate", ack(e, k, "APPLIED", "APPLIED"), "UNREADABLE"),
        c("unknown-outcome", ack(e, k, "ACCEPTED"), "UNREADABLE"),
        c("other-event-id", ack(other, k, "APPLIED"), "UNREADABLE"),
        c("other-effect-key", ack(e, k + "x", "APPLIED"), "UNREADABLE"),
        c("other-consumer", ack(e, k, "APPLIED", consumer="payment-api"), "UNREADABLE"),
        c("missing-consumer", ack(e, k, "APPLIED", consumer=None), "UNREADABLE"),
        c("not-json", "OK", "UNREADABLE"),
    ]


REJECT_CODES = OrderedDict([("400", ["ENVELOPE_INVALID"]), ("409", ["EVENT_ID_CONFLICT", "EFFECT_KEY_CONFLICT"]), ("422", ["CONTENT_INVALID"])])

STATUS_CLASSIFICATION = OrderedDict([
    ("200", "ACK"), ("400", "REJECTED_WITH_CODE"), ("409", "REJECTED_WITH_CODE"), ("422", "REJECTED_WITH_CODE"),
    ("302", "RETRYABLE"), ("401", "RETRYABLE"), ("404", "RETRYABLE"), ("411", "RETRYABLE"), ("413", "RETRYABLE"), ("415", "RETRYABLE"),
    ("429", "RETRYABLE"), ("500", "RETRYABLE"), ("502", "RETRYABLE"), ("503", "RETRYABLE"),
])


def status_cases():
    """상태 + 본문 → 분류(m-1). REJECTED는 (상태, 계약 코드) 짝이 맞을 때만."""
    def c(status, body, expected):
        return OrderedDict([("status", status), ("body", body), ("expected", expected)])
    err = lambda code: json.dumps(OrderedDict([("error", code)]), separators=(",", ":"))
    return [
        c(400, err("ENVELOPE_INVALID"), "REJECTED"),
        c(409, err("EVENT_ID_CONFLICT"), "REJECTED"),
        c(409, err("EFFECT_KEY_CONFLICT"), "REJECTED"),
        c(422, err("CONTENT_INVALID"), "REJECTED"),
        c(400, err("QUERY_NOT_ALLOWED"), "RETRYABLE"),
        c(400, err("CONTENT_INVALID"), "RETRYABLE"),
        c(422, err("ENVELOPE_INVALID"), "RETRYABLE"),
        c(409, err("CONFLICT"), "RETRYABLE"),
        c(422, "<html>Unprocessable</html>", "RETRYABLE"),
        c(400, "", "RETRYABLE"),
        c(401, err("UNAUTHENTICATED"), "RETRYABLE"),
        c(503, err("TEMPORARILY_UNAVAILABLE"), "RETRYABLE"),
    ]


def golden_text():
    golden = OrderedDict([
        ("description", "FIN-FACT-RELAY-001 PAYMENT -> FINANCIAL payment fact channel v1 (generated independently of both apps with Python hmac/hashlib/json; "
                        "Demo keys and identifiers, not production values)."),
        ("header", HEADER_NAME),
        ("headerPattern", "^" + HEADER_RE.pattern + "$"),
        ("canonicalTag", CANONICAL_TAG),
        ("method", METHOD),
        ("path", PATH),
        ("canonical", "tag LF METHOD LF path LF t LF lowercase-hex(sha256(body)); UTF-8; no trailing LF"),
        ("hmacKey", "base64-decoded secret bytes"),
        ("windowSeconds", WINDOW_SECONDS),
        ("checkOrder", ["MALFORMED", "STALE", "UNKNOWN_KID", "SIGNATURE"]),
        ("consumer", CONSUMER),
        ("keys", [OrderedDict([("kid", k), ("secretBase64", v)]) for k, v in KEYS.items()]),
        ("envelopes", envelope_vectors()),
        ("verifyCases", verify_cases()),
        ("ackCases", ack_cases()),
        ("statusClassification", STATUS_CLASSIFICATION),
        ("rejectCodes", REJECT_CODES),
        ("statusCases", status_cases()),
    ])
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


class PaymentFactRelayGolden(unittest.TestCase):
    def test_golden_files_of_both_apps_equal_the_independent_generation(self):
        expected = golden_text()
        for path in TARGETS:
            with open(path, encoding="utf-8", newline="") as f:
                self.assertEqual(f.read(), expected, path)

    def test_demo_keys_are_at_least_32_bytes_and_distinct(self):
        decoded = [base64.b64decode(v) for v in KEYS.values()]
        self.assertTrue(all(len(k) >= 32 for k in decoded))
        self.assertEqual(len(set(decoded)), len(decoded))

    def test_window_is_inclusive_at_300_seconds(self):
        cases = {c["name"]: c["expected"] for c in verify_cases()}
        self.assertEqual(cases["accept-window-minus-300"]["result"], "ACCEPT")
        self.assertEqual(cases["accept-window-plus-300"]["result"], "ACCEPT")
        self.assertEqual(cases["reject-window-minus-301"]["reason"], "STALE")
        self.assertEqual(cases["reject-window-plus-301"]["reason"], "STALE")

    def test_every_rejection_reason_is_covered_and_all_are_401(self):
        cases = verify_cases()
        self.assertEqual({c["expected"]["reason"] for c in cases}, {None, "MALFORMED", "STALE", "UNKNOWN_KID", "SIGNATURE"})
        for c in cases:
            if c["expected"]["result"] == "REJECT":
                self.assertEqual(c["expected"]["httpStatus"], 401, c["name"])

    def test_envelope_vectors_verify_and_effect_keys_follow_the_transaction(self):
        for v in envelope_vectors():
            self.assertEqual(verify(v["body"], v["header"], NOW, ALL_KIDS)[0], "ACCEPT", v["input"]["name"])
            payload = json.loads(v["input"]["payloadJson"])
            if v["input"]["schemaVersion"] == 1:
                self.assertEqual(v["input"]["effectKey"], v["input"]["eventType"] + ":" + payload["transactionId"])

    def test_payload_key_order_is_the_read_order_not_the_producer_order(self):
        # MySQL가 정규화한 순서(길이 순)가 본문에 그대로 남는다 — 재전송 바이트가 같다는 근거.
        b = json.loads(envelope_vectors()[0]["body"], object_pairs_hook=OrderedDict)
        keys = list(b["payload"].keys())
        self.assertEqual(keys, sorted(keys, key=lambda k: (len(k.encode("utf-8")), k.encode("utf-8"))))

    def test_only_content_refusals_with_a_contract_code_are_quarantined(self):
        self.assertEqual({s for s, r in STATUS_CLASSIFICATION.items() if r == "REJECTED_WITH_CODE"}, set(REJECT_CODES.keys()))
        for case in status_cases():
            body = case["body"]
            try:
                code = json.loads(body).get("error") if body else None
            except ValueError:
                code = None
            expected = "REJECTED" if code in REJECT_CODES.get(str(case["status"]), []) else "RETRYABLE"
            self.assertEqual(case["expected"], expected, case)


if __name__ == "__main__":
    if "--write" in sys.argv:
        for target in TARGETS:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(golden_text())
    else:
        unittest.main()
