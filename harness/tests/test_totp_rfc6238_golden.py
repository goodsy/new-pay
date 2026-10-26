"""internal-admin TOTP 검증기의 golden(RFC 6238 Appendix B, SHA-1)을 Kotlin 구현과 독립적으로 고정한다 (ADMIN-BOOT-001 S3 — §7.2 TotpVerifierSpec).

golden 파일의 모든 코드를 Python 표준 hmac·hashlib(RFC 4226 동적 절단)으로 다시 계산해 비교하고, RFC 본문에 실린 8자리 값과도 대조한다.
Kotlin TotpVerifierSpec은 같은 golden을 읽어 구현을 확인한다 — 두 구현이 서로를 베끼지 않는다.
"""
import base64
import hashlib
import hmac
import json
import struct
import unittest
from pathlib import Path

GOLDEN = Path(__file__).resolve().parents[2] / "apps/internal-admin/src/test/resources/contracts/totp-rfc6238.golden.json"

# RFC 6238 Appendix B 표의 SHA1 열(8자리) — 원문 값.
RFC_SHA1_8_DIGITS = {
    59: "94287082",
    1111111109: "07081804",
    1111111111: "14050471",
    1234567890: "89005924",
    2000000000: "69279037",
    20000000000: "65353130",
}


def hotp(secret: bytes, counter: int, digits: int) -> str:
    mac = hmac.new(secret, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = mac[-1] & 0x0F
    binary = struct.unpack(">I", mac[offset:offset + 4])[0] & 0x7FFFFFFF
    return str(binary % (10 ** digits)).zfill(digits)


class TotpRfc6238Golden(unittest.TestCase):

    def setUp(self):
        self.golden = json.loads(GOLDEN.read_text(encoding="utf-8"))

    def test_golden_secret_is_the_rfc_ascii_key_in_both_encodings(self):
        secret = self.golden["secretAscii"].encode("ascii")
        self.assertEqual(secret, b"12345678901234567890")
        self.assertEqual(base64.b32decode(self.golden["secretBase32"]), secret)
        self.assertEqual((self.golden["algorithm"], self.golden["periodSeconds"]), ("SHA1", 30))

    def test_every_golden_code_matches_an_independent_hotp(self):
        secret = self.golden["secretAscii"].encode("ascii")
        self.assertGreaterEqual(len(self.golden["vectors"]), 6)
        for v in self.golden["vectors"]:
            step = v["unixTime"] // self.golden["periodSeconds"]
            self.assertEqual(v["step"], step, v)
            self.assertEqual(v["code8"], hotp(secret, step, 8), v)
            self.assertEqual(v["code6"], hotp(secret, step, 6), v)
            self.assertEqual(v["code6"], v["code8"][-6:], v)

    def test_golden_contains_the_rfc_published_values(self):
        published = {v["unixTime"]: v["code8"] for v in self.golden["vectors"]}
        self.assertEqual({t: published.get(t) for t in RFC_SHA1_8_DIGITS}, RFC_SHA1_8_DIGITS)


if __name__ == "__main__":
    unittest.main()
