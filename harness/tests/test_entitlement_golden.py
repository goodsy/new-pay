"""권리 Snapshot 계약 golden의 생성 절차(재현 가능·두 앱 코드와 독립).

명세 §10 정규 JSON 규칙(키 사전순, 공백 없음, UTC ms 시각 문자열, 정수 10진, null, `"`·`\\`·제어문자만 escape)을 Python 표준 라이브러리
(`json.dumps(sort_keys=True, separators=(',', ':'), ensure_ascii=False)`)로 구현해 golden을 만들고, 앱 시험 자원의 파일과 바이트 비교한다.
golden 값에는 제어문자가 없으므로 escape 규칙 차이(\\n 표기 등)가 끼지 않는다. 서명 키는 시험 전용 값이다.

- v1(MASTER-SNAPSHOT-001 S7): PAYMENT만 — PAYMENT는 v1을 계속 해석한다(배포 전환 기간의 기존 head). MASTER는 v2만 조립하므로(MASTER-SNAPSHOT-002
  §5.1) MASTER 시험 자원의 v1 golden은 v2 golden으로 대체했다.
- v2(MASTER-SNAPSHOT-002 §4.1): MASTER·PAYMENT 둘 다 — v1 내용 + accounts[].limitPolicies·policyAssignments·cardRoutes. 값은 Demo(운영값 아님).

`python3 harness/tests/test_entitlement_golden.py --write`로 파일을 다시 쓸 수 있다(검토된 계약 변경일 때만).
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


def resource(app, name):
    return os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", name)


TARGETS = {
    1: [resource("payment-api", "entitlement-snapshot-v1.golden.json")],
    2: [resource(app, "entitlement-snapshot-v2.golden.json") for app in ("master-api", "payment-api")],
}
KEY_ID = "test-k1"
KEY_B64 = "dGVzdC1vbmx5LWVudGl0bGVtZW50LXNpZ25pbmcta2V5LTEh"
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def canon(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def ulid(prefix, tail):
    body = ("01J9ZQG1DEN" + tail).ljust(26, "0")[:26]
    assert all(c in CROCKFORD for c in body)
    return f"{prefix}_{body}"


def golden_text(schema_version):
    m, b = ulid("mer", "M1"), ulid("biz", "B1")
    s1, s2 = ulid("site", "S1"), ulid("site", "S2")
    mid, asg, csa = ulid("mid", "A1"), ulid("asg", "P1"), ulid("csa", "C1")
    t0 = "2026-10-09T02:00:00.000Z"
    account = {
        "mid": mid, "lifecycle": "ACTIVE", "blocked": False, "issuingAgreementId": csa, "serviceKind": "CARD_PG_PAYMENT",
        "assignments": [{"assignmentId": asg, "siteId": s1, "role": "PRIMARY", "status": "ACTIVE", "effectiveFrom": t0, "effectiveTo": None}],
        "contractWindows": [{"siteId": s1, "from": t0, "to": None},
                            {"siteId": s2, "from": t0, "to": "2026-10-09T03:00:00.000Z"}],
        "activations": [{"paymentMethod": "CARD", "status": "ACTIVE"}],
        "siteConfirmations": [{"siteId": s1, "paymentMethod": "CARD"}],
        "currencies": [{"currency": "KRW", "minorUnit": 0, "from": t0, "to": "2026-12-31T15:00:00.000Z"}],
    }
    ids = {}
    if schema_version == 2:
        ids = {"limitDefaultId": ulid("tlp", "D1"), "limitOverrideId": ulid("tlp", "D2"), "feeAssignmentId": ulid("pas", "F1"),
               "settlementAssignmentId": ulid("pas", "T1"), "feeVersionId": ulid("pv", "V1"), "settlementVersionId": ulid("pv", "V2"),
               "cardRouteId": ulid("cra", "R1")}
        # Demo 값(운영값 아님): CSA 기본 KRW 건당 1,000,000 / 일 5,000,000, MID Override 건당 300,000 / 일 무제한(명시). 경계는 Seoul 자정(UTC 15:00).
        account["limitPolicies"] = [
            {"policyId": ids["limitDefaultId"], "scope": "CSA_DEFAULT", "agreementId": csa, "currency": "KRW",
             "perTransaction": {"kind": "LIMITED", "amountMinor": 1000000}, "daily": {"kind": "LIMITED", "amountMinor": 5000000},
             "from": "2026-10-09T15:00:00.000Z", "to": None},
            {"policyId": ids["limitOverrideId"], "scope": "MID_OVERRIDE", "agreementId": None, "currency": "KRW",
             "perTransaction": {"kind": "LIMITED", "amountMinor": 300000}, "daily": {"kind": "UNLIMITED", "amountMinor": None},
             "from": "2026-10-10T15:00:00.000Z", "to": None},
        ]
        account["policyAssignments"] = [
            {"assignmentId": ids["feeAssignmentId"], "kind": "FEE_SCHEDULE", "agreementId": csa, "currency": "KRW",
             "policyVersionId": ids["feeVersionId"], "policyCode": "FEE-DEMO", "versionNo": 1, "contentStatus": "DEMO", "from": t0, "to": None},
            {"assignmentId": ids["settlementAssignmentId"], "kind": "SETTLEMENT_POLICY", "agreementId": csa, "currency": "KRW",
             "policyVersionId": ids["settlementVersionId"], "policyCode": "STL-DEMO", "versionNo": 1, "contentStatus": "DEMO", "from": t0, "to": None},
        ]
        account["cardRoutes"] = [
            {"activationId": ids["cardRouteId"], "paymentMethod": "CARD", "acquirerCode": "SIMCARD01", "routeRef": "SIM-VAN-01",
             "institutionMerchantRef": "SIMMERCH0001", "status": "ACTIVE"},
        ]
    content = {
        "merchantId": m, "businessId": b, "revision": 7,
        "party": {"businessLifecycle": "ACTIVE", "merchantLifecycle": "ACTIVE", "businessBlocked": False, "merchantBlocked": False},
        "sites": [{"siteId": s1, "lifecycle": "ACTIVE", "reviewStatus": "APPROVED"},
                  {"siteId": s2, "lifecycle": "ACTIVE", "reviewStatus": "PENDING"}],
        "accounts": [account],
    }
    content_canonical = canon(content)
    content_hash = "ec1:" + hashlib.sha256(content_canonical.encode("utf-8")).hexdigest()
    wire = dict(content)
    wire.update({"contentHash": content_hash, "issuedAt": "2026-10-09T02:00:05.000Z", "leaseSeq": 0, "leaseUntil": "2026-10-09T02:01:05.000Z"})
    wire_canonical = canon(wire)
    event_id = "0f1e2d3c-4b5a-4987-8a6b-5c4d3e2f1a0b" if schema_version == 1 else "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    # 봉투 v1 필드 순서는 MASTER EntitlementEnvelope.body와 같다(본문 바이트가 서명 대상).
    body = ('{"envelopeVersion":1,"eventId":"%s","eventType":"EntitlementSnapshot","schemaVersion":%d,"producer":"master-api",'
            '"aggregateId":"%s","aggregateVersion":7,"occurredAt":"2026-10-09T02:00:05.000Z","payload":%s}') % (event_id, schema_version, m, wire_canonical)
    signature = f"keyId={KEY_ID},v1=" + hmac.new(base64.b64decode(KEY_B64), body.encode("utf-8"), hashlib.sha256).hexdigest()
    description = {
        1: "MASTER-SNAPSHOT-001 S7 contract golden (generated independently of both apps from spec §10 canonical rules). Test-only key.",
        2: "MASTER-SNAPSHOT-002 schemaVersion 2 contract golden (authorization terms; Demo values, not production). "
           "Generated independently of both apps from spec §10 canonical rules. Test-only key.",
    }[schema_version]
    golden = OrderedDict([
        ("description", description),
        ("eventId", event_id), ("merchantId", m), ("businessId", b), ("siteIds", [s1, s2]), ("mid", mid), ("assignmentId", asg), ("agreementId", csa),
    ])
    golden.update(ids)
    golden.update([
        ("revision", 7), ("issuedAt", "2026-10-09T02:00:05.000Z"), ("leaseSeq", 0), ("leaseUntil", "2026-10-09T02:01:05.000Z"),
        ("contentCanonical", content_canonical), ("contentHash", content_hash), ("wirePayloadCanonical", wire_canonical), ("envelopeBody", body),
        ("signingKeyId", KEY_ID), ("signingKeyBase64", KEY_B64), ("signatureHeader", signature),
    ])
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


class EntitlementGoldenTest(unittest.TestCase):
    def test_v1_golden_file_equals_the_independent_generation(self):
        expected = golden_text(1)
        for path in TARGETS[1]:
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, path)

    def test_v2_golden_files_of_both_apps_equal_the_independent_generation(self):
        expected = golden_text(2)
        for path in TARGETS[2]:
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, path)


if __name__ == "__main__":
    if "--write" in sys.argv:
        for version, targets in TARGETS.items():
            for target in targets:
                with open(target, "w", encoding="utf-8", newline="\n") as f:
                    f.write(golden_text(version))
    else:
        unittest.main()
