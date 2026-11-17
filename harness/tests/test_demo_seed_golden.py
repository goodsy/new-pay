"""DEMO-SEED-001 Demo 가맹점 seed의 최신 권리 Snapshot golden 생성 절차(재현 가능·두 앱 코드와 독립 — 명세 §6, m7).

값은 명세 §4.2 표의 리터럴(D-DEMO-003 한도 1건 1,000,000원·1일 5,000,000원, DEMO 정책 버전, Simulator 매입사·Demo Route, host `.test`)이고, 정규 JSON 규칙은
`test_entitlement_golden.py`와 같다(키 사전순, 공백 없음, UTC ms 시각, `ensure_ascii=False`). 앱 구현 출력을 복사하지 않는다 — 시험이 앱과 이 생성 결과를 비교한다.

- 식별자는 시험 고정 ID(ULID 꼴 placeholder)다: MASTER `DemoSeedSnapshotGoldenSpec`이 시험 ID 생성기에 같은 값을 넣고 seed를 돌린 뒤 최신 Snapshot 정규 내용을 바이트 비교한다.
- 시계: MASTER 시험 기본 시각 2026-10-09T02:00:00.000Z(11:00 KST), T0 = 다음 Seoul 자정 2026-10-09T15:00:00.000Z. PRIMARY 연결 효력 시작 = MID 발급 시각(소급 금지 D-MD-005).
- revision 12(코드 분석): 업무 입력을 바꾸는 단계 03·05(MID 전 — 발행 없음)·07~12·15~18(발행 10건)이 각각 entitlement revision +1. 01·02·04·06·13·14는 revision 무관.
- PAYMENT `DemoSeedSnapshotAdmissionSpec`이 같은 파일을 실제 Inbox로 적용해 T0−1ms 거절·T0 허용을 확인한다(I-10).
- MERCHANT-CODE-001 MC-4: MASTER가 v4(v3 + 최상위 `merchantCode`)만 조립하므로 최신 Snapshot golden도 schemaVersion 4(`demo-seed-snapshot-v4`, seed 코드 `demoshop01`
  — MERCHANT-CODE-001 C-6)로 대체했다. revision은 그대로(seed는 코드를 바꾸지 않는다).

앱 사이 정적 정합(파일로만 읽는다 — Gradle 의존 없음):
- seed 카드사 개통 매입사 = Simulator 가상 카드 카탈로그(VirtualCard.kt)의 매입사(전 카드 같은 값) — 하드코딩 drift 방지.
- seed Route = `.env.example` payment-api Route 등록부(PAYCORE_PAYMENT_AUTHORIZATION_JSON)의 TEST Route 이름.
- production 상수 `DemoMerchantSeedValues.kt`의 값 = 이 golden의 리터럴.

`python3 harness/tests/test_demo_seed_golden.py --write`로 파일을 다시 쓸 수 있다(검토된 계약 변경일 때만).
"""
import hashlib
import json
import os
import re
import sys
import unittest
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
# 기대값 변경(MERCHANT-CODE-001 §6·MC-4): v3 → v4 파일(MASTER v4 조립).
NAME = "demo-seed-snapshot-v4.golden.json"
TARGETS = [os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", NAME) for app in ("master-api", "payment-api")]

# §4.2 Demo 값(운영값 아님).
SITE_HOST = "demo-shop.paycore.test"
ACQUIRER = "SIMACQA"
ROUTE_REF = "SIMVAN01"
INSTITUTION_MERCHANT_REF = "SIMVAN-DEMO-0001"
PER_TRANSACTION = 1000000
DAILY = 5000000
FEE_CODE = "DEMO-FEE"
SETTLEMENT_CODE = "DEMO-SETTLEMENT"
ISSUED_AT = "2026-10-09T02:00:00.000Z"
T0 = "2026-10-09T15:00:00.000Z"
REVISION = 12
# MERCHANT-CODE-001 C-6 seed 고정 코드(Demo — 운영값 아님).
MERCHANT_CODE = "demoshop01"


def read(*parts):
    with open(os.path.join(ROOT, *parts), encoding="utf-8") as f:
        return f.read()


def canon(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def ulid(prefix, tail):
    body = ("01J9ZQG1DEN" + tail).ljust(26, "0")[:26]
    assert all(c in CROCKFORD for c in body)
    return f"{prefix}_{body}"


def ids():
    return OrderedDict([
        ("merchantId", ulid("mer", "DM1")), ("businessId", ulid("biz", "DB1")), ("siteId", ulid("site", "DS1")), ("mid", ulid("mid", "DA1")),
        ("siteAssignmentId", ulid("asg", "DP1")), ("agreementId", ulid("csa", "DC1")), ("limitPolicyId", ulid("tlp", "DD1")),
        ("feeAssignmentId", ulid("pas", "DF1")), ("settlementAssignmentId", ulid("pas", "DT1")), ("feeVersionId", ulid("pv", "DV1")),
        ("settlementVersionId", ulid("pv", "DV2")), ("cardRouteId", ulid("cra", "DR1")),
    ])


def content():
    i = ids()
    account = {
        "mid": i["mid"], "lifecycle": "ACTIVE", "blocked": False, "issuingAgreementId": i["agreementId"], "serviceKind": "CARD_PG_PAYMENT",
        "assignments": [{"assignmentId": i["siteAssignmentId"], "siteId": i["siteId"], "role": "PRIMARY", "status": "ACTIVE",
                         "effectiveFrom": ISSUED_AT, "effectiveTo": None}],
        "contractWindows": [{"siteId": i["siteId"], "from": T0, "to": None}],
        "activations": [{"paymentMethod": "CARD", "status": "ACTIVE"}],
        "siteConfirmations": [{"siteId": i["siteId"], "paymentMethod": "CARD"}],
        "currencies": [{"currency": "KRW", "minorUnit": 0, "from": T0, "to": None}],
        "limitPolicies": [{"policyId": i["limitPolicyId"], "scope": "CSA_DEFAULT", "agreementId": i["agreementId"], "currency": "KRW",
                           "perTransaction": {"kind": "LIMITED", "amountMinor": PER_TRANSACTION}, "daily": {"kind": "LIMITED", "amountMinor": DAILY},
                           "from": T0, "to": None}],
        "policyAssignments": [
            {"assignmentId": i["feeAssignmentId"], "kind": "FEE_SCHEDULE", "agreementId": i["agreementId"], "currency": "KRW",
             "policyVersionId": i["feeVersionId"], "policyCode": FEE_CODE, "versionNo": 1, "contentStatus": "DEMO", "from": T0, "to": None},
            {"assignmentId": i["settlementAssignmentId"], "kind": "SETTLEMENT_POLICY", "agreementId": i["agreementId"], "currency": "KRW",
             "policyVersionId": i["settlementVersionId"], "policyCode": SETTLEMENT_CODE, "versionNo": 1, "contentStatus": "DEMO", "from": T0, "to": None},
        ],
        "cardRoutes": [{"activationId": i["cardRouteId"], "paymentMethod": "CARD", "acquirerCode": ACQUIRER, "routeRef": ROUTE_REF,
                        "institutionMerchantRef": INSTITUTION_MERCHANT_REF, "status": "ACTIVE"}],
    }
    return {
        "merchantId": i["merchantId"], "businessId": i["businessId"], "revision": REVISION,
        "party": {"businessLifecycle": "ACTIVE", "merchantLifecycle": "ACTIVE", "businessBlocked": False, "merchantBlocked": False},
        "sites": [{"siteId": i["siteId"], "lifecycle": "ACTIVE", "reviewStatus": "APPROVED", "channel": {"type": "WEB", "host": SITE_HOST}}],
        "accounts": [account],
        "merchantCode": MERCHANT_CODE,
    }


def golden_text():
    content_canonical = canon(content())
    golden = OrderedDict([
        ("description", "DEMO-SEED-001 latest entitlement snapshot after the 18-step Demo merchant seed (schemaVersion 4 — MERCHANT-CODE-001 MC-4 merchantCode; "
                        "Demo values, NOT operational — D-DEMO-001/003). Generated independently of both apps from spec §4.2 literals and the §10 canonical rules "
                        "of MASTER-SNAPSHOT-001."),
        ("schemaVersion", 4), ("issuedAt", ISSUED_AT), ("effectiveFrom", T0), ("revision", REVISION),
    ])
    golden.update(ids())
    golden.update([
        ("contentCanonical", content_canonical),
        ("contentHash", "ec1:" + hashlib.sha256(content_canonical.encode("utf-8")).hexdigest()),
    ])
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


SEED_VALUES = read("apps", "master-api", "src", "main", "kotlin", "com", "paycore", "master", "merchant", "adapter", "batch", "DemoMerchantSeedValues.kt")
CATALOG = read("apps", "institution-simulator", "src", "main", "kotlin", "com", "paycore", "simulator", "authentication", "domain", "VirtualCard.kt")
CARD_ENTRY = re.compile(r'^\s*VC_[A-Z0-9_]+\("(VC-[A-Z0-9-]+)", "([^"]*)", "([^"]*)", "([^"]*)", "[^"]*", CardScenario\.[A-Z0-9_]+\),$', re.M)


def seed_literal(constructor):
    found = re.findall(constructor + r'\("([^"]*)"\)', SEED_VALUES)
    assert len(found) == 1, (constructor, found)
    return found[0]


class DemoSeedGoldenTest(unittest.TestCase):
    def test_golden_files_of_both_apps_equal_the_independent_generation(self):
        expected = golden_text()
        for path in TARGETS:
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, path)

    def test_production_seed_constants_are_the_spec_literals(self):
        self.assertEqual(seed_literal("AcquirerCode"), ACQUIRER)
        self.assertEqual(seed_literal("RouteRef"), ROUTE_REF)
        self.assertEqual(seed_literal("InstitutionMerchantRef"), INSTITUTION_MERCHANT_REF)
        self.assertEqual(re.findall(r'const val SITE_HOST = "([^"]*)"', SEED_VALUES), [SITE_HOST])
        self.assertEqual(re.findall(r"perTransactionLimit = LimitValue\.Limited\(([0-9_]+)\)", SEED_VALUES), ["1_000_000"])
        self.assertEqual(re.findall(r"dailyLimit = LimitValue\.Limited\(([0-9_]+)\)", SEED_VALUES), ["5_000_000"])
        self.assertEqual(re.findall(r'feePolicyCode = PolicyCode\("([^"]*)"\)', SEED_VALUES), [FEE_CODE])
        self.assertEqual(re.findall(r'settlementPolicyCode = PolicyCode\("([^"]*)"\)', SEED_VALUES), [SETTLEMENT_CODE])
        self.assertEqual(re.findall(r'merchantCode = MerchantCode\("([^"]*)"\)', SEED_VALUES), [MERCHANT_CODE])

    def test_seed_acquirer_is_the_simulator_catalog_acquirer(self):
        cards = {ref: acquirer for ref, _issuer, acquirer, _provider in CARD_ENTRY.findall(CATALOG)}
        self.assertGreaterEqual(len(cards), 30, "catalog parse found too few cards")
        self.assertEqual(len(cards), CATALOG.count('("VC-'), "every catalog entry is parsed")
        # PAYMENT 승인 시작 판정(A1)은 인증 결과 매입사로 (CARD, 매입사) 카드사 개통을 찾는다 — 모든 가상 카드가 seed 개통에 맞아야 한다.
        self.assertEqual(set(cards.values()), {seed_literal("AcquirerCode")})
        self.assertEqual(cards["VC-CREDIT-OK"], ACQUIRER)

    def test_seed_route_is_a_test_route_of_the_payment_api_demo_registry(self):
        env = read(".env.example")
        line = [l for l in env.splitlines() if l.startswith("PAYCORE_PAYMENT_AUTHORIZATION_JSON=")]
        self.assertEqual(len(line), 1)
        registry = json.loads(line[0].split("=", 1)[1].strip("'"))["paycore"]["authorization"]["routes"]
        route = seed_literal("RouteRef")
        self.assertIn(route, registry)
        self.assertEqual(registry[route]["environment"], "TEST")

    def test_golden_carries_every_authorization_start_condition_with_demo_markers(self):
        account = json.loads(json.loads(golden_text())["contentCanonical"])["accounts"][0]
        self.assertEqual([p["scope"] for p in account["limitPolicies"]], ["CSA_DEFAULT"])
        self.assertEqual(sorted((p["kind"], p["contentStatus"]) for p in account["policyAssignments"]),
                         [("FEE_SCHEDULE", "DEMO"), ("SETTLEMENT_POLICY", "DEMO")])
        self.assertEqual([(r["paymentMethod"], r["acquirerCode"], r["routeRef"], r["status"]) for r in account["cardRoutes"]],
                         [("CARD", ACQUIRER, ROUTE_REF, "ACTIVE")])
        self.assertTrue(SITE_HOST.endswith(".test"))


if __name__ == "__main__":
    if "--write" in sys.argv:
        for target in TARGETS:
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(golden_text())
    else:
        unittest.main()
