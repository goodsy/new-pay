"""CP-API-001 요청 fingerprint r1 golden(CP-CONTRACT-001 §7, CP-API-001 명세 §6.2)의 생성 절차(재현 가능·앱 코드와 독립).

정의를 Python 표준 라이브러리(hashlib)로 따로 구현해 golden을 만들고, payment-api 시험 자원의 파일과 바이트 비교한다:
token(s) = <UTF-8 byte 길이>":"s, canonical = token("paycore.create-payment-request") + token("1") + 필드마다 token(name) + ("1"+token(value) | "0"),
r1 = "r1:" + sha256(canonical UTF-8) lowercase hex. 값은 Demo(운영 MID·Site 아님).
`python3 harness/tests/test_create_payment_request_golden.py --write`로 파일을 다시 쓸 수 있다(검토된 계약 변경일 때만).
"""
import hashlib
import json
import os
import sys
import unittest
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TARGET = os.path.join(ROOT, "apps", "payment-api", "src", "test", "resources", "contracts", "create-payment-request-r1.golden.json")
FIELDS = ["merchantAccountId", "merchantSiteId", "merchantOrderId", "amount", "currency", "paymentMethod", "orderName"]


def token(s):
    return f"{len(s.encode('utf-8'))}:{s}"


def canonical(values):
    out = token("paycore.create-payment-request") + token("1")
    for name in FIELDS:
        out += token(name)
        value = values.get(name)
        out += ("1" + token(value)) if value is not None else "0"
    return out


def vectors():
    base = OrderedDict([
        ("merchantAccountId", "mid_01HZY3K8Q4M2N6P8R0T2V4X6Z8"),
        ("merchantSiteId", "site_01HZY3K8Q4M2N6P8R0T2V4X6Z9"),
        ("merchantOrderId", "order-0001"),
        ("amount", "10000"),
        ("currency", "KRW"),
        ("paymentMethod", "CARD"),
    ])
    cases = [
        ("A orderName present (ASCII)", dict(base, orderName="Demo T-shirt")),
        ("B orderName absent", dict(base)),
        ("C USD, non-ASCII orderName (Hangul + supplementary U+1F600)", dict(base, amount="1050", currency="USD", orderName="데모 상품 \U0001F600")),
        ("D same as A except one trailing space in orderName", dict(base, orderName="Demo T-shirt ")),
        ("E amount = Long.MAX_VALUE", dict(base, amount="9223372036854775807", orderName="x")),
    ]
    result = []
    for name, values in cases:
        c = canonical(values)
        result.append(OrderedDict([
            ("name", name),
            ("fields", [[f, values.get(f)] for f in FIELDS]),
            ("canonical", c),
            ("canonicalUtf8Bytes", len(c.encode("utf-8"))),
            ("fingerprint", "r1:" + hashlib.sha256(c.encode("utf-8")).hexdigest()),
        ]))
    return result


def golden_text():
    golden = OrderedDict([
        ("description", "CP-API-001 request fingerprint r1 golden vectors, generated independently of the app from CP-CONTRACT-001 §7. Demo values."),
        ("vectors", vectors()),
    ])
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


class CreatePaymentRequestGoldenTest(unittest.TestCase):
    def test_payment_api_golden_file_equals_the_independent_generation(self):
        with open(TARGET, encoding="utf-8") as f:
            self.assertEqual(f.read(), golden_text())

    def test_absent_and_present_order_name_differ_and_match_the_spec_values(self):
        by_name = {v["name"][0]: v["fingerprint"] for v in vectors()}
        self.assertEqual(by_name["A"], "r1:cc6b69c4f397c03694e0e2e90e8f506be1ad2b616006a949ae6e9425f31b24fc")
        self.assertEqual(by_name["B"], "r1:66a20aca332812e0bd433cbbfd8a3a81ef24da3e0e8b8bda21ab4dc3b7190a49")
        self.assertEqual(len(set(by_name.values())), 5)


if __name__ == "__main__":
    if "--write" in sys.argv:
        with open(TARGET, "w", encoding="utf-8", newline="\n") as f:
            f.write(golden_text())
    else:
        unittest.main()
