"""WEB host 문법 공유 시험 벡터(AUTHN-001 §17.4, 검토 n-f).

같은 문법을 세 곳이 구현한다: MASTER `SiteChannel`(WEB), PAYMENT Snapshot v3 수신(`SiteChannelEntry.Web`), PAYMENT `MerchantReturnUrl`의 host 부분. 이 시험이
벡터(허용·거절)를 Python 표준 라이브러리로 만들어 두 앱 시험 자원의 `host-grammar-vectors.json`과 바이트 비교하고, 각 앱 시험이 같은 파일을 읽어 판정한다 —
세 구현이 갈라지지 않음을 한 파일로 고정한다. 문법: 소문자 [a-z0-9-] label 둘 이상, label 1..63(하이픈은 가운데만), 전체 ≤ 253, 끝 점 없음, 마지막 label이
10진수이거나 `0x`(+16진수)이면 IP 리터럴(D-MD-014). 값은 Demo host(운영 가맹점 도메인 아님).
`python3 harness/tests/test_host_grammar_vectors.py --write`로 다시 쓴다.
"""
import json
import os
import re
import sys
import unittest
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
NAME = "host-grammar-vectors.json"
TARGETS = [os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", NAME) for app in ("master-api", "payment-api")]

LONGEST = ".".join(["a" * 63] * 3 + ["b" * 61])          # 253
TOO_LONG = ".".join(["a" * 63] * 3 + ["b" * 62])         # 254

ACCEPTED = ["shop.example.test", "a.b", "xn--9n2bp8q.example.test", "a-b.example.test", "0a.example.test", "shop.example.co.kr", "shop.0xg",
            "0x7f.example", ("a" * 63) + ".example.test", LONGEST]
REJECTED = ["", "Shop.example.test", "shop.example.test.", "localhost", "-a.example.test", "a-.example.test", "a..example.test",
            ("a" * 64) + ".example.test", TOO_LONG, "127.0.0.1", "2130706433", "a.0x7f", "a.0x", "shop_example.test", "샵.example.test",
            "shop.example.test:443", "user@shop.example.test", "[::1]"]


def reference_accepts(host):
    """독립 참조 구현(앱 코드와 무관 — 문법 정의 그대로)."""
    if len(host) > 253 or not re.fullmatch(r"[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+", host):
        return False
    return not re.fullmatch(r"[0-9]+|0x[0-9a-f]*", host.rsplit(".", 1)[-1])


def vectors_text():
    data = OrderedDict([
        ("description", "AUTHN-001 shared WEB host grammar vectors (SiteChannel WEB, Snapshot v3 receiver, MerchantReturnUrl host). Demo hosts."),
        ("accepted", ACCEPTED),
        ("rejected", REJECTED),
    ])
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


class HostGrammarVectors(unittest.TestCase):
    def test_vector_files_of_both_apps_equal_the_generation(self):
        expected = vectors_text()
        for path in TARGETS:
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, path)

    def test_vectors_agree_with_the_reference_grammar(self):
        self.assertEqual(len(LONGEST), 253)
        self.assertEqual(len(TOO_LONG), 254)
        for host in ACCEPTED:
            self.assertTrue(reference_accepts(host), host)
        for host in REJECTED:
            self.assertFalse(reference_accepts(host), host)


if __name__ == "__main__":
    if "--write" in sys.argv:
        for target in TARGETS:
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(vectors_text())
    else:
        unittest.main()
