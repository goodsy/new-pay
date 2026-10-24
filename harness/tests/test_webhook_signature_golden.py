"""가맹점 Webhook 서명 계약 v1 golden(WEBHOOK-001 §6.1·§21.0, D-WH-003, WH-19·WH-20, 검토 m-8 — 두 앱 코드와 독립 생성).

PG(apps/payment-api, `HmacMerchantWebhookSigner`)와 가상 가맹점 수신기(apps/institution-simulator `merchant` 모듈)는 Gradle 의존 없이 이 파일 하나로 서명 계약을 공유한다.
이 시험은 Python 표준 라이브러리(`hmac`·`hashlib`)로 golden을 만들어 두 앱 시험 자원의 파일과 바이트 비교한다. payment-api 시험은 서명 벡터의 헤더를 그대로 만들고,
Simulator 시험은 검증 사례마다 같은 판정(통과/거절·분류)을 내야 한다.

계약 요점:
- 헤더 `PayCore-Signature: t=<Unix 초>,kid=<id>,v1=<64 소문자 hex>[,kid=<id>,v1=<hex>]` — 공백 없음, t 먼저, 활성 키 순서대로 최대 2쌍.
- HMAC-SHA256 키 = 가맹점에 보이는 비밀 문자열 전체(`whsec_…`)의 UTF-8 바이트, 서명 대상 = t의 ASCII 숫자 + "." + 본문 바이트 그대로(본문은 UTF-8 JSON).
- 검증자: 쉼표로 나눈 key=value 중 모르는 key는 무시, t 정확히 1개(1~10자리 숫자), (kid, v1) 쌍 1~2개(kid 다음에 바로 v1), v1은 소문자 hex 64자만,
  자기 키(kid)와 일치하는 쌍 하나라도 맞으면 통과(상수 시간 비교), |now − t| ≤ 300초(Demo, 경계 포함) — −300·+300 통과, −301·+301 거절.
- 거절 분류: MALFORMED(문법), STALE(창 밖), SIGNATURE(아는 kid가 없거나 어느 쌍도 맞지 않음). 판정 순서 = 문법 → 창 → 서명.
값은 Demo(운영 비밀·MID 아님). `python3 harness/tests/test_webhook_signature_golden.py --write`로 다시 쓴다(검토된 계약 변경일 때만).
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
NAME = "webhook-signature-v1.golden.json"
TARGETS = [os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", NAME) for app in ("institution-simulator", "payment-api")]

WINDOW_SECONDS = 300
NOW = 1792500300


def demo_secret(tag):
    """시험 전용 비밀: `whsec_` + base64url(무 padding) — payment-api 설정 문법(43자 이상)을 만족한다."""
    return "whsec_" + base64.urlsafe_b64encode(("test-only-webhook-golden-key-" + tag).encode("ascii")).decode("ascii").rstrip("=")


KEYS = OrderedDict([("whk_t_golden_1", demo_secret("0001")), ("whk_t_golden_2", demo_secret("0002"))])

BODY_EXPIRED = ('{"eventId":"whe_01JB0000000000000000000001","type":"payment.expired","schemaVersion":1,"environment":"TEST",'
                '"merchantAccountId":"mid_demo_0001","createdAt":"2026-10-22T00:00:00.000Z","sequence":1,'
                '"payment":{"paymentId":"pay_demo_0001","merchantOrderId":"order-0001","amount":10000,"currency":"KRW","status":"EXPIRED"}}')
BODY_UTF8 = ('{"eventId":"whe_01JB0000000000000000000002","type":"payment.failed","schemaVersion":1,"environment":"TEST",'
             '"merchantAccountId":"mid_demo_0001","createdAt":"2026-10-22T00:00:01.000Z","sequence":1,'
             '"payment":{"paymentId":"pay_demo_0002","merchantOrderId":"주문-0002","amount":5000,"currency":"KRW","status":"FAILED","failureCategory":"DECLINED"}}')

HEX64 = re.compile(r"^[0-9a-f]{64}$")
T_DIGITS = re.compile(r"^[0-9]{1,10}$")


def v1(secret, t, body):
    return hmac.new(secret.encode("utf-8"), str(t).encode("ascii") + b"." + body.encode("utf-8"), hashlib.sha256).hexdigest()


def header(t, body, kids):
    return "t=%d" % t + "".join(",kid=%s,v1=%s" % (k, v1(KEYS[k], t, body)) for k in kids)


def verify(header_value, body, known, now):
    """독립 검증 구현(Simulator 판정의 기준). known = 수신기가 아는 {kid: secret}."""
    tokens = []
    for part in header_value.split(","):
        key, sep, value = part.partition("=")
        if not sep:
            return "MALFORMED"
        if key in ("t", "kid", "v1"):
            tokens.append((key, value))
    ts = [v for k, v in tokens if k == "t"]
    rest = [(k, v) for k, v in tokens if k != "t"]
    if len(ts) != 1 or not T_DIGITS.match(ts[0]) or len(rest) not in (2, 4):
        return "MALFORMED"
    pairs = []
    for i in range(0, len(rest), 2):
        (k1, kid), (k2, sig) = rest[i], rest[i + 1]
        if k1 != "kid" or k2 != "v1" or not HEX64.match(sig):
            return "MALFORMED"
        pairs.append((kid, sig))
    t = int(ts[0])
    if abs(now - t) > WINDOW_SECONDS:
        return "STALE"
    for kid, sig in pairs:
        if kid in known and hmac.compare_digest(v1(known[kid], t, body), sig):
            return "ACCEPTED"
    return "SIGNATURE"


def golden_text():
    signing = [
        OrderedDict([("name", "single-key"), ("t", 1792500000), ("keyIds", ["whk_t_golden_1"]), ("body", BODY_EXPIRED),
                     ("header", header(1792500000, BODY_EXPIRED, ["whk_t_golden_1"]))]),
        OrderedDict([("name", "rotation-two-keys-utf8-body"), ("t", 1792500001), ("keyIds", ["whk_t_golden_1", "whk_t_golden_2"]), ("body", BODY_UTF8),
                     ("header", header(1792500001, BODY_UTF8, ["whk_t_golden_1", "whk_t_golden_2"]))]),
    ]
    only1 = ["whk_t_golden_1"]
    good = header(NOW, BODY_EXPIRED, only1)
    sig_now = v1(KEYS["whk_t_golden_1"], NOW, BODY_EXPIRED)
    cases = [
        ("window-minus-300-accepted", header(NOW - 300, BODY_EXPIRED, only1), BODY_EXPIRED, only1),
        ("window-plus-300-accepted", header(NOW + 300, BODY_EXPIRED, only1), BODY_EXPIRED, only1),
        ("window-minus-301-stale", header(NOW - 301, BODY_EXPIRED, only1), BODY_EXPIRED, only1),
        ("window-plus-301-stale", header(NOW + 301, BODY_EXPIRED, only1), BODY_EXPIRED, only1),
        ("body-one-byte-changed", good, BODY_EXPIRED.replace("10000", "10001"), only1),
        ("unknown-key-id-only", header(NOW, BODY_EXPIRED, ["whk_t_golden_2"]), BODY_EXPIRED, only1),
        ("rotation-retired-first-key-known-second", header(NOW, BODY_EXPIRED, ["whk_t_golden_2", "whk_t_golden_1"]), BODY_EXPIRED, only1),
        ("rotation-both-known-utf8-body", header(NOW, BODY_UTF8, ["whk_t_golden_1", "whk_t_golden_2"]), BODY_UTF8, ["whk_t_golden_1", "whk_t_golden_2"]),
        ("unknown-parameter-ignored", good + ",v0=ignored", BODY_EXPIRED, only1),
        ("upper-case-hex", "t=%d,kid=whk_t_golden_1,v1=%s" % (NOW, sig_now.upper()), BODY_EXPIRED, only1),
        ("two-timestamps", "t=%d,t=%d,kid=whk_t_golden_1,v1=%s" % (NOW, NOW, sig_now), BODY_EXPIRED, only1),
        ("missing-timestamp", "kid=whk_t_golden_1,v1=%s" % sig_now, BODY_EXPIRED, only1),
        ("three-pairs", good + ",kid=whk_t_golden_2,v1=%s,kid=whk_t_golden_3,v1=%s" % (sig_now, sig_now), BODY_EXPIRED, only1),
        ("signature-without-key-id", "t=%d,v1=%s" % (NOW, sig_now), BODY_EXPIRED, only1),
        ("space-after-comma", "t=%d, kid=whk_t_golden_1, v1=%s" % (NOW, sig_now), BODY_EXPIRED, only1),
        ("token-without-equals", good + ",garbage", BODY_EXPIRED, only1),
        ("current-accepted", good, BODY_EXPIRED, only1),
    ]
    verification = [
        OrderedDict([("name", name), ("header", h), ("body", body), ("receiverKeyIds", kids), ("expected", verify(h, body, {k: KEYS[k] for k in kids}, NOW))])
        for name, h, body, kids in cases
    ]
    golden = OrderedDict([
        ("description", "WEBHOOK-001 merchant webhook signature contract v1 (generated independently of both apps with Python hmac; Demo secrets, not production). "
                        "HMAC-SHA256 key = UTF-8 bytes of the whole secret string; signed bytes = ASCII t + '.' + body bytes."),
        ("headerName", "PayCore-Signature"),
        ("windowSeconds", WINDOW_SECONDS),
        ("now", NOW),
        ("keys", [OrderedDict([("keyId", k), ("secret", s)]) for k, s in KEYS.items()]),
        ("signing", signing),
        ("verification", verification),
    ])
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


class WebhookSignatureGolden(unittest.TestCase):
    def test_golden_files_of_both_apps_equal_the_independent_generation(self):
        expected = golden_text()
        for path in TARGETS:
            with open(path, encoding="utf-8", newline="") as f:
                self.assertEqual(f.read(), expected, path)

    def test_window_edges_are_inclusive_at_300_and_refused_at_301(self):
        verdicts = {c["name"]: c["expected"] for c in json.loads(golden_text())["verification"]}
        self.assertEqual(verdicts["window-minus-300-accepted"], "ACCEPTED")
        self.assertEqual(verdicts["window-plus-300-accepted"], "ACCEPTED")
        self.assertEqual(verdicts["window-minus-301-stale"], "STALE")
        self.assertEqual(verdicts["window-plus-301-stale"], "STALE")

    def test_every_verdict_class_is_covered_and_secrets_follow_the_issued_grammar(self):
        golden = json.loads(golden_text())
        self.assertEqual({c["expected"] for c in golden["verification"]}, {"ACCEPTED", "STALE", "SIGNATURE", "MALFORMED"})
        for k in golden["keys"]:
            self.assertRegex(k["secret"], r"^whsec_[A-Za-z0-9_-]{43,128}$")
        self.assertIn("주문", golden["signing"][1]["body"])

    def test_the_independent_verifier_rejects_tampering(self):
        known = {"whk_t_golden_1": KEYS["whk_t_golden_1"]}
        h = header(NOW, BODY_EXPIRED, ["whk_t_golden_1"])
        self.assertEqual(verify(h, BODY_EXPIRED, known, NOW), "ACCEPTED")
        self.assertEqual(verify(h, BODY_EXPIRED + " ", known, NOW), "SIGNATURE")
        self.assertEqual(verify(h.replace("t=%d" % NOW, "t=%d" % (NOW + 1)), BODY_EXPIRED, known, NOW), "SIGNATURE")


if __name__ == "__main__":
    if "--write" in sys.argv:
        for target in TARGETS:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(golden_text())
    else:
        unittest.main()
