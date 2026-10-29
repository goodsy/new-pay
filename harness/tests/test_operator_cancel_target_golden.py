"""운영자 보정 취소 승인 대상 지문 `oct1:` golden(ADMIN-PAYMENT-OPS-001 §4.19 ①, CANCEL-CONTRACT CC-13, ADMIN-BOOT-001 F-4 — 두 앱 코드와 독립 생성).

PAYMENT(apps/payment-api `OperatorCancelTarget.fingerprint()`)와 ADMIN(apps/internal-admin — 승인 시점에 같은 지문을 따로 계산, S4)은 코드를 공유하지 않고
이 파일 하나로 정규화 규칙을 맞춘다. 이 시험은 Python 표준 라이브러리(`hashlib`)만으로 지문을 만들어 두 앱 시험 자원의 파일과 바이트 비교한다.

정규화(canonical) 규칙:
- 요소 11개를 순서대로: tag `paycore.operator-cancel-target`, 버전 "1", paymentId, 금액(minor 10진), 통화, 반환 잔액 revision(10진), approvalId,
  요청자, 승인자, 목적 종류(`INCIDENT_OR_RECON_CORRECTION`|`MERCHANT_TERMINATED_OBLIGATION`), 근거 참조. 사유 문구는 넣지 않는다.
- 요소마다 `<그 요소의 UTF-8 바이트 수>:<값>`을 구분자 없이 이어 붙인다(길이 접두 — 문자 수가 아니라 바이트 수).
- 지문 = "oct1:" + 소문자 hex(SHA-256(canonical의 UTF-8 바이트)).
값은 Demo(운영 ID 아님). `python3 harness/tests/test_operator_cancel_target_golden.py --write`로 다시 쓴다(검토된 계약 변경일 때만).
"""
import hashlib
import json
import os
import sys
import unittest
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
NAME = "operator-cancel-target-oct1.golden.json"
TARGETS = [os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", NAME) for app in ("internal-admin", "payment-api")]

DOMAIN_TAG = "paycore.operator-cancel-target"
VERSION = "1"
LONG_MAX = 9223372036854775807

PAY = "pay_01JC0000000000000000000001"
PAY_USD = "pay_01JC0000000000000000000002"
APR = "apr_01JC0000000000000000000001"
APR_OTHER = "apr_01JC0000000000000000000002"
REQUESTER = "adm_01JC00000000000000000000R1"
APPROVER = "adm_01JC00000000000000000000A1"


def canonical(v):
    parts = [DOMAIN_TAG, VERSION, v["paymentId"], str(v["amountMinor"]), v["currency"], str(v["balanceRevision"]), v["approvalId"],
             v["requestedBy"], v["approvedBy"], v["purposeKind"], v["purposeReference"]]
    return "".join("%d:%s" % (len(p.encode("utf-8")), p) for p in parts)


def fingerprint(v):
    return "oct1:" + hashlib.sha256(canonical(v).encode("utf-8")).hexdigest()


def vector(name, **overrides):
    v = OrderedDict([
        ("name", name),
        ("paymentId", PAY),
        ("amountMinor", 10000),
        ("currency", "KRW"),
        ("balanceRevision", 3),
        ("approvalId", APR),
        ("requestedBy", REQUESTER),
        ("approvedBy", APPROVER),
        ("purposeKind", "INCIDENT_OR_RECON_CORRECTION"),
        ("purposeReference", "RECON-2026-0001"),
    ])
    v.update(overrides)
    v["canonical"] = canonical(v)
    v["fingerprint"] = fingerprint(v)
    return v


def vectors():
    return [
        vector("ascii-basic"),
        # 한글(UTF-8 3바이트)과 보조 평면 문자 U+20000(UTF-8 4바이트, UTF-16 2단위) — 길이 접두가 바이트 수임을 고정한다.
        vector("hangul-and-supplementary-reference", purposeReference="대사-2026-0001 \U00020000"),
        vector("merchant-terminated-obligation", purposeKind="MERCHANT_TERMINATED_OBLIGATION", purposeReference="TERM-2026-0007"),
        vector("revision-zero", balanceRevision=0),
        vector("revision-long-max", balanceRevision=LONG_MAX),
        vector("usd-partial", paymentId=PAY_USD, amountMinor=1050, currency="USD"),
        vector("requester-approver-swapped", requestedBy=APPROVER, approvedBy=REQUESTER),
        vector("amount-one", amountMinor=1),
        vector("other-approval-id", approvalId=APR_OTHER),
    ]


def golden_text():
    golden = OrderedDict([
        ("description", "ADMIN-PAYMENT-OPS-001 operator correction cancel approval target fingerprint oct1 (generated independently of both apps with Python hashlib; "
                        "Demo identifiers). canonical = concatenation of '<utf8 byte length>:<value>' for the 11 elements; fingerprint = 'oct1:' + hex(sha256(canonical))."),
        ("domainTag", DOMAIN_TAG),
        ("version", VERSION),
        ("elements", ["domainTag", "version", "paymentId", "amountMinor", "currency", "balanceRevision", "approvalId", "requestedBy", "approvedBy",
                      "purposeKind", "purposeReference"]),
        ("vectors", vectors()),
    ])
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


class OperatorCancelTargetGolden(unittest.TestCase):
    def test_golden_files_of_both_apps_equal_the_independent_generation(self):
        expected = golden_text()
        for path in TARGETS:
            with open(path, encoding="utf-8", newline="") as f:
                self.assertEqual(f.read(), expected, path)

    def test_length_prefix_counts_utf8_bytes_not_characters(self):
        v = {x["name"]: x for x in vectors()}["hangul-and-supplementary-reference"]
        ref = v["purposeReference"]
        self.assertNotEqual(len(ref), len(ref.encode("utf-8")))
        self.assertTrue(v["canonical"].endswith("%d:%s" % (len(ref.encode("utf-8")), ref)))

    def test_every_vector_has_a_distinct_fingerprint_and_swapping_participants_changes_it(self):
        vs = vectors()
        prints = [v["fingerprint"] for v in vs]
        self.assertEqual(len(set(prints)), len(prints))
        by = {v["name"]: v["fingerprint"] for v in vs}
        self.assertNotEqual(by["ascii-basic"], by["requester-approver-swapped"])
        for p in prints:
            self.assertRegex(p, r"^oct1:[0-9a-f]{64}$")

    def test_reason_text_is_not_an_element(self):
        self.assertNotIn("reason", json.loads(golden_text())["elements"])


if __name__ == "__main__":
    if "--write" in sys.argv:
        for target in TARGETS:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(golden_text())
    else:
        unittest.main()
