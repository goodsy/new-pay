"""가상 인증기관 계약 v1 golden(AUTHN-001 §13.4·§17.4 — 두 앱 코드와 독립 생성).

PG(apps/payment-api, HTTP Adapter)와 Simulator(apps/institution-simulator)는 Gradle 의존 없이 이 파일 하나로 계약을 공유한다. 이 시험은 Python 표준 라이브러리로
golden을 만들어 두 앱 시험 자원의 파일과 바이트 비교한다. Simulator 시험은 자기 응답이 golden과 같은 JSON임을, payment-api adapter 시험은 golden을 파싱해 Port
결과가 됨을 확인한다(S7).

계약 요점(§13.4, m-E·m-4): 등록 요청 닫힌 필드 9개, 시각은 UTC ms(`…T…:….SSSZ`), 금액은 minor 정수, plan은 {kind[, months]}. 조회 응답의 증거 6필드
(cardType·issuerCode·acquirerCode·providerCode·evidenceRef·authenticatedAt)는 AUTHENTICATED에만 있고 다른 outcome에는 **없다**(null 아님 — 키 부재).
cardType은 열린 토큰 `^[A-Z][A-Z0-9_]{0,31}$`(PayCore 지원 판정은 PG domain R10). malformedLookups는 계약 위반 벡터(PG adapter가 Unverifiable(MALFORMED)로
바꿔야 한다). 값은 Demo(운영 MID·카드사·URL·키 아님). `python3 harness/tests/test_authentication_contract_golden.py --write`로 다시 쓴다(검토된 계약 변경일 때만).
"""
import json
import os
import sys
import unittest
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
NAME = "authentication-contract-v1.golden.json"
TARGETS = [os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", NAME) for app in ("institution-simulator", "payment-api")]

CHECKOUT_ORIGIN = "https://checkout.example.test"
CUSTOMER_BASE = "https://sim.example.test"
HANDLE = "Hh0123456789abcdefghij"
REGISTERED_AT = "2026-10-16T01:00:00.000Z"
COMPLETED_AT = "2026-10-16T01:00:30.000Z"
EXPIRES_AT = "2026-10-16T01:10:00.000Z"      # 등록 + 거래 TTL 10분(Demo)
VALID_UNTIL = "2026-10-16T01:05:30.000Z"     # 완료 + 결과 유효기간 5분(Demo)


def b64url_id(prefix, tag):
    body = (tag + "0" * 22)[:22]
    return prefix + body


def state_of(tag):
    return (tag + "x" * 43)[:43]


def register_request(tag):
    return OrderedDict([
        ("paymentId", "pay_demo_" + tag), ("merchantRef", "mid-demo-0001"), ("environment", "TEST"), ("amountMinor", 10000), ("currency", "KRW"),
        ("plan", OrderedDict([("kind", "LUMP_SUM")])), ("cardCompany", "SIMISS-A"), ("state", state_of("state" + tag)),
        ("returnUrl", f"{CHECKOUT_ORIGIN}/checkout/s/{HANDLE}/authentication/return"),
    ])


def scenario(tag, card, outcome, valid_until=VALID_UNTIL):
    req = register_request(tag)
    tx = b64url_id("sat_", "Tx" + tag)
    ref = b64url_id("srr_", "Rr" + tag)
    lookup = OrderedDict([
        ("resultRef", ref), ("authenticationTransactionId", tx), ("outcome", outcome), ("state", req["state"]),
        ("binding", OrderedDict([(k, req[k]) for k in ("paymentId", "merchantRef", "environment", "amountMinor", "currency", "plan", "cardCompany")])),
    ])
    if outcome == "AUTHENTICATED":
        lookup.update([("cardType", "CREDIT"), ("issuerCode", "SIMISS-A"), ("acquirerCode", "SIMACQ-A"), ("providerCode", "SIMAUTH-1"),
                       ("evidenceRef", "sim-ev-" + tag), ("authenticatedAt", COMPLETED_AT)])
    lookup["validUntil"] = valid_until
    return OrderedDict([
        ("card", card),
        ("registerRequest", req),
        ("registerResponse", OrderedDict([("authenticationTransactionId", tx), ("customerUrl", f"{CUSTOMER_BASE}/sim/authenticate/{tx}"), ("expiresAt", EXPIRES_AT)])),
        ("redirect", f"{req['returnUrl']}?resultRef={ref}&state={req['state']}"),
        ("lookup", lookup),
    ])


def golden_text():
    scenarios = OrderedDict([
        ("authenticated", scenario("A", "VC-CREDIT-OK", "AUTHENTICATED")),
        ("failed", scenario("F", "VC-AUTH-FAIL", "FAILED")),
        ("cancelled", scenario("C", None, "CANCELLED", valid_until=COMPLETED_AT)),
        ("expired", scenario("E", "VC-EXPIRED", "EXPIRED", valid_until=COMPLETED_AT)),
    ])
    ok = scenarios["authenticated"]["lookup"]
    failed = scenarios["failed"]["lookup"]
    malformed = OrderedDict([
        ("authenticatedWithoutEvidenceRef", OrderedDict((k, v) for k, v in ok.items() if k != "evidenceRef")),
        ("failedWithIssuerCode", OrderedDict(list(failed.items()) + [("issuerCode", "SIMISS-A")])),
        ("unknownField", OrderedDict(list(ok.items()) + [("extra", True)])),
        ("nullCardType", OrderedDict((k, (None if k == "cardType" else v)) for k, v in ok.items())),
        ("amountAsString", OrderedDict((k, (OrderedDict(list(v.items())[:3] + [("amountMinor", "10000")] + list(v.items())[4:]) if k == "binding" else v)) for k, v in ok.items())),
        ("lowerCaseCardType", OrderedDict((k, ("credit" if k == "cardType" else v)) for k, v in ok.items())),
    ])
    golden = OrderedDict([
        ("description", "AUTHN-001 virtual authentication institution contract v1 (generated independently of both apps; Demo values, not production). "
                        "Evidence fields exist only for AUTHENTICATED; cardType is an open token."),
        ("checkoutOrigin", CHECKOUT_ORIGIN), ("customerBaseUrl", CUSTOMER_BASE), ("registeredAt", REGISTERED_AT), ("completedAt", COMPLETED_AT),
        ("transactionTtl", "PT10M"), ("resultValidity", "PT5M"),
        ("scenarios", scenarios),
        ("malformedLookups", malformed),
    ])
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


class AuthenticationContractGolden(unittest.TestCase):
    def test_golden_files_of_both_apps_equal_the_independent_generation(self):
        expected = golden_text()
        for path in TARGETS:
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, path)

    def test_evidence_fields_exist_only_for_authenticated(self):
        evidence = {"cardType", "issuerCode", "acquirerCode", "providerCode", "evidenceRef", "authenticatedAt"}
        for name, s in json.loads(golden_text())["scenarios"].items():
            keys = set(s["lookup"].keys())
            if s["lookup"]["outcome"] == "AUTHENTICATED":
                self.assertTrue(evidence <= keys, name)
            else:
                self.assertFalse(evidence & keys, name)


if __name__ == "__main__":
    if "--write" in sys.argv:
        for target in TARGETS:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(golden_text())
    else:
        unittest.main()
