"""MASTER-SNAPSHOT-001 S7 계약 golden의 생성 절차(재현 가능·두 앱 코드와 독립).

명세 §10 정규 JSON 규칙(키 사전순, 공백 없음, UTC ms 시각 문자열, 정수 10진, null, `"`·`\\`·제어문자만 escape)을 Python 표준 라이브러리
(`json.dumps(sort_keys=True, separators=(',', ':'), ensure_ascii=False)`)로 구현해 golden을 만들고, 두 앱 시험 자원의 파일과 바이트 비교한다.
golden 값에는 제어문자가 없으므로 escape 규칙 차이(\\n 표기 등)가 끼지 않는다. 서명 키는 시험 전용 값이다.
`python3 harness/tests/test_entitlement_golden.py --write`로 두 파일을 다시 쓸 수 있다(검토된 계약 변경일 때만).
"""
import base64
import hashlib
import hmac
import json
import os
import sys
import unittest
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TARGETS = [os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", "entitlement-snapshot-v1.golden.json")
           for app in ("master-api", "payment-api")]
KEY_ID = "test-k1"
KEY_B64 = "dGVzdC1vbmx5LWVudGl0bGVtZW50LXNpZ25pbmcta2V5LTEh"
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def canon(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def ulid(prefix, tail):
    body = ("01J9ZQG1DEN" + tail).ljust(26, "0")[:26]
    assert all(c in CROCKFORD for c in body)
    return f"{prefix}_{body}"


def golden_text():
    m, b = ulid("mer", "M1"), ulid("biz", "B1")
    s1, s2 = ulid("site", "S1"), ulid("site", "S2")
    mid, asg, csa = ulid("mid", "A1"), ulid("asg", "P1"), ulid("csa", "C1")
    t0 = "2026-10-09T02:00:00.000Z"
    content = {
        "merchantId": m, "businessId": b, "revision": 7,
        "party": {"businessLifecycle": "ACTIVE", "merchantLifecycle": "ACTIVE", "businessBlocked": False, "merchantBlocked": False},
        "sites": [{"siteId": s1, "lifecycle": "ACTIVE", "reviewStatus": "APPROVED"},
                  {"siteId": s2, "lifecycle": "ACTIVE", "reviewStatus": "PENDING"}],
        "accounts": [{
            "mid": mid, "lifecycle": "ACTIVE", "blocked": False, "issuingAgreementId": csa, "serviceKind": "CARD_PG_PAYMENT",
            "assignments": [{"assignmentId": asg, "siteId": s1, "role": "PRIMARY", "status": "ACTIVE", "effectiveFrom": t0, "effectiveTo": None}],
            "contractWindows": [{"siteId": s1, "from": t0, "to": None},
                                {"siteId": s2, "from": t0, "to": "2026-10-09T03:00:00.000Z"}],
            "activations": [{"paymentMethod": "CARD", "status": "ACTIVE"}],
            "siteConfirmations": [{"siteId": s1, "paymentMethod": "CARD"}],
            "currencies": [{"currency": "KRW", "minorUnit": 0, "from": t0, "to": "2026-12-31T15:00:00.000Z"}],
        }],
    }
    content_canonical = canon(content)
    content_hash = "ec1:" + hashlib.sha256(content_canonical.encode("utf-8")).hexdigest()
    wire = dict(content)
    wire.update({"contentHash": content_hash, "issuedAt": "2026-10-09T02:00:05.000Z", "leaseSeq": 0, "leaseUntil": "2026-10-09T02:01:05.000Z"})
    wire_canonical = canon(wire)
    event_id = "0f1e2d3c-4b5a-4987-8a6b-5c4d3e2f1a0b"
    # 봉투 v1 필드 순서는 MASTER EntitlementEnvelope.body와 같다(본문 바이트가 서명 대상).
    body = ('{"envelopeVersion":1,"eventId":"%s","eventType":"EntitlementSnapshot","schemaVersion":1,"producer":"master-api",'
            '"aggregateId":"%s","aggregateVersion":7,"occurredAt":"2026-10-09T02:00:05.000Z","payload":%s}') % (event_id, m, wire_canonical)
    signature = f"keyId={KEY_ID},v1=" + hmac.new(base64.b64decode(KEY_B64), body.encode("utf-8"), hashlib.sha256).hexdigest()
    golden = OrderedDict([
        ("description", "MASTER-SNAPSHOT-001 S7 contract golden (generated independently of both apps from spec §10 canonical rules). Test-only key."),
        ("eventId", event_id), ("merchantId", m), ("businessId", b), ("siteIds", [s1, s2]), ("mid", mid), ("assignmentId", asg), ("agreementId", csa),
        ("revision", 7), ("issuedAt", "2026-10-09T02:00:05.000Z"), ("leaseSeq", 0), ("leaseUntil", "2026-10-09T02:01:05.000Z"),
        ("contentCanonical", content_canonical), ("contentHash", content_hash), ("wirePayloadCanonical", wire_canonical), ("envelopeBody", body),
        ("signingKeyId", KEY_ID), ("signingKeyBase64", KEY_B64), ("signatureHeader", signature),
    ])
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


class EntitlementGoldenTest(unittest.TestCase):
    def test_both_app_golden_files_equal_the_independent_generation(self):
        expected = golden_text()
        for path in TARGETS:
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, path)


if __name__ == "__main__":
    if "--write" in sys.argv:
        for target in TARGETS:
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(golden_text())
    else:
        unittest.main()
