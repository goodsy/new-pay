"""권리 Snapshot 계약 golden의 생성 절차(재현 가능·두 앱 코드와 독립).

명세 §10 정규 JSON 규칙(키 사전순, 공백 없음, UTC ms 시각 문자열, 정수 10진, null, `"`·`\\`·제어문자만 escape)을 Python 표준 라이브러리
(`json.dumps(sort_keys=True, separators=(',', ':'), ensure_ascii=False)`)로 구현해 golden을 만들고, 앱 시험 자원의 파일과 바이트 비교한다.
golden 값에는 제어문자가 없으므로 escape 규칙 차이(\\n 표기 등)가 끼지 않는다. 서명 키는 시험 전용 값이다.

- v1(MASTER-SNAPSHOT-001 S7): PAYMENT만 — PAYMENT는 v1을 계속 해석한다(배포 전환 기간의 기존 head). MASTER는 v2만 조립하므로(MASTER-SNAPSHOT-002
  §5.1) MASTER 시험 자원의 v1 golden은 v2 golden으로 대체했다.
- v2(MASTER-SNAPSHOT-002 §4.1): v1 내용 + accounts[].limitPolicies·policyAssignments·cardRoutes. 값은 Demo(운영값 아님). AUTHN-001부터 MASTER는 v3만
  조립하므로 MASTER 시험 자원의 v2 golden은 v3 golden으로 대체했다(PAYMENT는 v2를 계속 해석 — PAYMENT 시험 자원에 남는다, v1과 같은 선례).
- v3(AUTHN-001 §9, D-AUTHN-002): MASTER·PAYMENT 둘 다 — v2 내용 + sites[].channel(WEB = {type, host}, APP = {type}만 — 앱 식별값은 싣지 않는다).
  host는 Demo 값(운영 가맹점 도메인 아님).
- v3 변형 `entitlement-snapshot-v3-currency-at-execution`(MERCHANT-OPS-002 §8.3 B3-2-4 골든 확장): MASTER·PAYMENT 둘 다 — v3와 같은 ID·내용에
  통화 구간만 `from` = AT_EXECUTION 확정 시각(lockedNow — KST 자정 아님·ms 있음 `…T02:00:07.123Z`)·`to` 없음, revision 8·발행 시각 = 그 확정 시각.
- v4(MERCHANT-CODE-001 §6·MC-4, D-MCODE-001 Q-MC-1·D-MCODE-002 Q-MC-8): MASTER·PAYMENT 둘 다 — v3 내용 + 최상위 `merchantCode`(가맹점 코드, 출력 전용 표시값 —
  금액·권리 의미 없음). MC-4부터 MASTER는 v4만 조립하므로 MASTER 시험 자원의 v3 golden·v3 변형은 v4 golden·v4 변형으로 대체했다(PAYMENT는 v3를 계속 해석 —
  PAYMENT 시험 자원에 남는다, v1·v2와 같은 선례). 코드 값은 Demo(형식 `^[a-z][a-z0-9]{5,13}$`).

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
    2: [resource("payment-api", "entitlement-snapshot-v2.golden.json")],
    # MERCHANT-CODE-001 MC-4: MASTER는 v4만 조립 → v3 golden은 PAYMENT에만 남는다(v1·v2 선례).
    3: [resource("payment-api", "entitlement-snapshot-v3.golden.json")],
    4: [resource(app, "entitlement-snapshot-v4.golden.json") for app in ("master-api", "payment-api")],
}
AT_EXECUTION_VARIANT = "currency-at-execution"
VARIANT_TARGETS = {
    (3, AT_EXECUTION_VARIANT): [resource("payment-api", "entitlement-snapshot-v3-currency-at-execution.golden.json")],
    (4, AT_EXECUTION_VARIANT): [resource(app, "entitlement-snapshot-v4-currency-at-execution.golden.json") for app in ("master-api", "payment-api")],
}
# MC-4 Demo 가맹점 코드(운영값 아님). 형식은 MASTER V13 CHECK와 같다.
MERCHANT_CODE = "goldshop01"
# B3-2 AT_EXECUTION 확정 시각(Demo): 자정이 아니고 ms가 있는 lockedNow. Snapshot은 같은 TX에서 발행되므로 발행 시각도 같다.
AT_EXECUTION_LOCKED_NOW = "2026-10-09T02:00:07.123Z"
KEY_ID = "test-k1"
KEY_B64 = "dGVzdC1vbmx5LWVudGl0bGVtZW50LXNpZ25pbmcta2V5LTEh"
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def canon(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def ulid(prefix, tail):
    body = ("01J9ZQG1DEN" + tail).ljust(26, "0")[:26]
    assert all(c in CROCKFORD for c in body)
    return f"{prefix}_{body}"


def golden_text(schema_version, variant=None):
    m, b = ulid("mer", "M1"), ulid("biz", "B1")
    s1, s2 = ulid("site", "S1"), ulid("site", "S2")
    mid, asg, csa = ulid("mid", "A1"), ulid("asg", "P1"), ulid("csa", "C1")
    t0 = "2026-10-09T02:00:00.000Z"
    revision, issued_at, lease_until = 7, "2026-10-09T02:00:05.000Z", "2026-10-09T02:01:05.000Z"
    currencies = [{"currency": "KRW", "minorUnit": 0, "from": t0, "to": "2026-12-31T15:00:00.000Z"}]
    if variant == AT_EXECUTION_VARIANT:
        assert schema_version in (3, 4)
        revision, issued_at, lease_until = 8, AT_EXECUTION_LOCKED_NOW, "2026-10-09T02:01:07.123Z"
        currencies = [{"currency": "KRW", "minorUnit": 0, "from": AT_EXECUTION_LOCKED_NOW, "to": None}]
    elif variant is not None:
        raise ValueError(variant)
    account = {
        "mid": mid, "lifecycle": "ACTIVE", "blocked": False, "issuingAgreementId": csa, "serviceKind": "CARD_PG_PAYMENT",
        "assignments": [{"assignmentId": asg, "siteId": s1, "role": "PRIMARY", "status": "ACTIVE", "effectiveFrom": t0, "effectiveTo": None}],
        "contractWindows": [{"siteId": s1, "from": t0, "to": None},
                            {"siteId": s2, "from": t0, "to": "2026-10-09T03:00:00.000Z"}],
        "activations": [{"paymentMethod": "CARD", "status": "ACTIVE"}],
        "siteConfirmations": [{"siteId": s1, "paymentMethod": "CARD"}],
        "currencies": currencies,
    }
    ids = {}
    if schema_version >= 2:
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
            {"activationId": ids["cardRouteId"], "paymentMethod": "CARD", "acquirerCode": "SIMCARD01", "routeRef": "SIMVAN01",
             "institutionMerchantRef": "SIMMERCH0001", "status": "ACTIVE"},
        ]
    sites = [{"siteId": s1, "lifecycle": "ACTIVE", "reviewStatus": "APPROVED"},
             {"siteId": s2, "lifecycle": "ACTIVE", "reviewStatus": "PENDING"}]
    if schema_version >= 3:
        sites[0]["channel"] = {"type": "WEB", "host": "shop.example.test"}
        sites[1]["channel"] = {"type": "APP"}
    content = {
        "merchantId": m, "businessId": b, "revision": revision,
        "party": {"businessLifecycle": "ACTIVE", "merchantLifecycle": "ACTIVE", "businessBlocked": False, "merchantBlocked": False},
        "sites": sites,
        "accounts": [account],
    }
    if schema_version >= 4:
        content["merchantCode"] = MERCHANT_CODE
    content_canonical = canon(content)
    content_hash = "ec1:" + hashlib.sha256(content_canonical.encode("utf-8")).hexdigest()
    wire = dict(content)
    wire.update({"contentHash": content_hash, "issuedAt": issued_at, "leaseSeq": 0, "leaseUntil": lease_until})
    wire_canonical = canon(wire)
    event_id = {1: "0f1e2d3c-4b5a-4987-8a6b-5c4d3e2f1a0b", 2: "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", 3: "2b3c4d5e-6f70-4a81-9b2c-3d4e5f6a7b8c",
                4: "4d5e6f70-8192-4ca3-9d4e-5f6a7b8c9d0e"}[schema_version]
    if variant == AT_EXECUTION_VARIANT:
        event_id = {3: "3c4d5e6f-7081-4b92-8c3d-4e5f6a7b8c9d", 4: "5e6f7081-92a3-4db4-8e5f-6a7b8c9d0e1f"}[schema_version]
    # 봉투 v1 필드 순서는 MASTER EntitlementEnvelope.body와 같다(본문 바이트가 서명 대상).
    body = ('{"envelopeVersion":1,"eventId":"%s","eventType":"EntitlementSnapshot","schemaVersion":%d,"producer":"master-api",'
            '"aggregateId":"%s","aggregateVersion":%d,"occurredAt":"%s","payload":%s}') % (event_id, schema_version, m, revision, issued_at, wire_canonical)
    signature = f"keyId={KEY_ID},v1=" + hmac.new(base64.b64decode(KEY_B64), body.encode("utf-8"), hashlib.sha256).hexdigest()
    description = {
        1: "MASTER-SNAPSHOT-001 S7 contract golden (generated independently of both apps from spec §10 canonical rules). Test-only key.",
        2: "MASTER-SNAPSHOT-002 schemaVersion 2 contract golden (authorization terms; Demo values, not production). "
           "Generated independently of both apps from spec §10 canonical rules. Test-only key.",
        3: "AUTHN-001 schemaVersion 3 contract golden (v2 + sites[].channel WEB host / APP type; Demo values, not production). "
           "Generated independently of both apps from spec §10 canonical rules. Test-only key.",
        4: "MERCHANT-CODE-001 MC-4 schemaVersion 4 contract golden (v3 + top-level merchantCode — display-only output field; Demo values, not production). "
           "Generated independently of both apps from spec §10 canonical rules. Test-only key.",
    }[schema_version]
    if variant == AT_EXECUTION_VARIANT:
        description = (f"MERCHANT-OPS-002 B3-2 schemaVersion {schema_version} golden variant: currency segment from = AT_EXECUTION lockedNow "
                       "(not KST midnight, milliseconds), open end; revision 8 issued at that instant. Demo values, not production. "
                       "Generated independently of both apps from spec §10 canonical rules. Test-only key.")
    golden = OrderedDict([
        ("description", description),
        ("eventId", event_id), ("merchantId", m), ("businessId", b), ("siteIds", [s1, s2]), ("mid", mid), ("assignmentId", asg), ("agreementId", csa),
    ])
    golden.update(ids)
    if schema_version >= 4:
        golden["merchantCode"] = MERCHANT_CODE
    golden.update([
        ("revision", revision), ("issuedAt", issued_at), ("leaseSeq", 0), ("leaseUntil", lease_until),
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
        # AUTHN-001: v2 golden은 PAYMENT에만 남는다(MASTER는 v3만 조립). 시험 이름은 하네스 시험 목록 안정성을 위해 유지한다.
        expected = golden_text(2)
        for path in TARGETS[2]:
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, path)
        self.assertFalse(os.path.exists(resource("master-api", "entitlement-snapshot-v2.golden.json")), "MASTER assembles v3 only")

    def test_v3_golden_files_of_both_apps_equal_the_independent_generation(self):
        # MERCHANT-CODE-001 MC-4: v3 golden은 PAYMENT에만 남는다(MASTER는 v4만 조립). 시험 이름은 하네스 시험 목록 안정성을 위해 유지한다.
        expected = golden_text(3)
        for path in TARGETS[3]:
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, path)
        self.assertFalse(os.path.exists(resource("master-api", "entitlement-snapshot-v3.golden.json")), "MASTER assembles v4 only")

    def test_v3_currency_at_execution_golden_files_of_both_apps_equal_the_independent_generation(self):
        # MERCHANT-CODE-001 MC-4: v3 변형도 PAYMENT에만(MASTER 쪽은 v4 변형으로 대체). 시험 이름은 유지한다.
        expected = golden_text(3, AT_EXECUTION_VARIANT)
        for path in VARIANT_TARGETS[(3, AT_EXECUTION_VARIANT)]:
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, path)
        self.assertFalse(os.path.exists(resource("master-api", "entitlement-snapshot-v3-currency-at-execution.golden.json")), "MASTER assembles v4 only")

    def test_v4_golden_files_of_both_apps_equal_the_independent_generation(self):
        expected = golden_text(4)
        for path in TARGETS[4]:
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, path)

    def test_v4_currency_at_execution_golden_files_of_both_apps_equal_the_independent_generation(self):
        expected = golden_text(4, AT_EXECUTION_VARIANT)
        for path in VARIANT_TARGETS[(4, AT_EXECUTION_VARIANT)]:
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, path)

    def test_v4_differs_from_v3_only_by_the_merchant_code_which_carries_no_internal_key(self):
        v3 = json.loads(json.loads(golden_text(3))["contentCanonical"])
        v4 = json.loads(json.loads(golden_text(4))["contentCanonical"])
        code = v4.pop("merchantCode")
        self.assertEqual(v3, v4)
        self.assertRegex(code, r"^[a-z][a-z0-9]{5,13}$")
        self.assertFalse(code.startswith(("mer_", "biz_")))
        variant3 = json.loads(json.loads(golden_text(3, AT_EXECUTION_VARIANT))["contentCanonical"])
        variant4 = json.loads(json.loads(golden_text(4, AT_EXECUTION_VARIANT))["contentCanonical"])
        self.assertEqual(variant4.pop("merchantCode"), code)
        self.assertEqual(variant3, variant4)

    def test_v3_currency_at_execution_differs_from_v3_only_by_currency_segment_and_revision(self):
        base = json.loads(json.loads(golden_text(3))["contentCanonical"])
        variant = json.loads(json.loads(golden_text(3, AT_EXECUTION_VARIANT))["contentCanonical"])
        self.assertEqual(variant["accounts"][0]["currencies"], [{"currency": "KRW", "from": "2026-10-09T02:00:07.123Z", "minorUnit": 0, "to": None}])
        for content in (base, variant):
            content["accounts"][0].pop("currencies")
            content.pop("revision")
        self.assertEqual(base, variant)

    def test_v3_differs_from_v2_only_by_site_channels(self):
        v2 = json.loads(json.loads(golden_text(2))["contentCanonical"])
        v3 = json.loads(json.loads(golden_text(3))["contentCanonical"])
        for site in v3["sites"]:
            site.pop("channel")
        self.assertEqual(v2, v3)


if __name__ == "__main__":
    if "--write" in sys.argv:
        for version, targets in TARGETS.items():
            for target in targets:
                with open(target, "w", encoding="utf-8", newline="\n") as f:
                    f.write(golden_text(version))
        for (version, variant), targets in VARIANT_TARGETS.items():
            for target in targets:
                with open(target, "w", encoding="utf-8", newline="\n") as f:
                    f.write(golden_text(version, variant))
    else:
        unittest.main()
