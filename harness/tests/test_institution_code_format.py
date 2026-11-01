"""기관 식별 코드 공통 형식 교차 검사(INST-CODE-001, D-INST-CODE-001 — 세 앱과 golden을 파일로만 읽는다).

매입사·발급사(카드사)·인증 기관·Route·VAN 기관 코드는 전 앱에서 `^[A-Z0-9]{1,32}$`(하이픈·밑줄 금지)다. 앱은 Gradle 의존 없이 계약(golden)으로만
공유하므로, 이 시험이 앱 사이의 정적 정합을 본다(명세 docs/repository/INST-CODE-001-INSTITUTION-CODE-FORMAT.md §4):
- IC-9: Simulator 가상 카드 카탈로그(VirtualCard.kt)의 발급사·매입사·인증 기관 코드가 형식을 따른다.
- IC-3: 인증 결과 golden(VC-CREDIT-OK)의 코드 = 카탈로그 VC-CREDIT-OK 코드, 그리고 그 매입사가 MASTER 최신 migration의 card_route_activation CHECK를
  통과한다(MASTER 개통 매입사 = Simulator 인증 결과 매입사가 성립할 수 있다).
- IC-10: 세 앱의 형식 정의 파일이 같은 문자열을 쓰고 이전 형식(`[A-Z0-9-]`·`[A-Z0-9_]`)을 쓰지 않는다.
- golden(인증·VAN·권리 Snapshot)의 정상 메시지 코드가 형식을 따른다(malformedLookups는 일부러 어긴 벡터라 제외).
"""
import glob
import json
import os
import re
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PATTERN = "^[A-Z0-9]{1,32}$"
CODE = re.compile(r"\A[A-Z0-9]{1,32}\Z")


def path(*parts):
    return os.path.join(ROOT, *parts)


def read(*parts):
    with open(path(*parts), encoding="utf-8") as f:
        return f.read()


SIM_MAIN = ("apps", "institution-simulator", "src", "main", "kotlin", "com", "paycore", "simulator")
CATALOG = read(*SIM_MAIN, "authentication", "domain", "VirtualCard.kt")
CARD_ENTRY = re.compile(r'^\s*VC_[A-Z0-9_]+\("(VC-[A-Z0-9-]+)", "([^"]*)", "([^"]*)", "([^"]*)", "[^"]*", CardScenario\.[A-Z0-9_]+\),$', re.M)


def catalog():
    return {ref: (issuer, acquirer, provider) for ref, issuer, acquirer, provider in CARD_ENTRY.findall(CATALOG)}


def golden(app, name):
    with open(path("apps", app, "src", "test", "resources", "contracts", name), encoding="utf-8") as f:
        return json.load(f)


def latest_master_check(column):
    """MASTER 업무 migration 중 card_route_activation의 해당 열 CHECK를 마지막으로 정의한 파일의 정규식."""
    found = None
    for sql in sorted(glob.glob(path("apps", "master-api", "src", "main", "resources", "db", "migration", "master", "V*__*.sql")),
                      key=lambda p: int(re.match(r"V(\d+)__", os.path.basename(p)).group(1))):
        with open(sql, encoding="utf-8") as f:
            for m in re.finditer(r"CHECK \(REGEXP_LIKE\(" + column + r", '([^']+)', 'c'\)\)", f.read()):
                found = (os.path.basename(sql), m.group(1))
    return found


class InstitutionCodeFormat(unittest.TestCase):
    def test_catalog_codes_follow_the_common_format(self):
        cards = catalog()
        self.assertGreaterEqual(len(cards), 30, "catalog parse found too few cards")
        self.assertEqual(len(cards), CATALOG.count('("VC-'), "every catalog entry is parsed")
        for ref, codes in cards.items():
            for code in codes:
                self.assertRegex(code, CODE, ref)

    def test_authentication_golden_evidence_is_the_catalog_card_and_passes_the_master_check(self):
        issuer, acquirer, provider = catalog()["VC-CREDIT-OK"]
        for app in ("institution-simulator", "payment-api", "master-api"):
            s = golden(app, "authentication-contract-v1.golden.json")["scenarios"]["authenticated"]
            self.assertEqual(s["card"], "VC-CREDIT-OK")
            lookup = s["lookup"]
            self.assertEqual((lookup["issuerCode"], lookup["acquirerCode"], lookup["providerCode"]), (issuer, acquirer, provider), app)
            self.assertEqual(s["registerRequest"]["cardCompany"], issuer, app)
        for column in ("acquirer_code", "route_ref"):
            source, regex = latest_master_check(column)
            self.assertEqual(regex, PATTERN, f"{source} {column}")
        self.assertRegex(acquirer, re.compile(latest_master_check("acquirer_code")[1].replace("^", r"\A").replace("$", r"\Z")))

    def test_format_definitions_of_every_app_use_the_same_pattern(self):
        definitions = {
            "MASTER": ("apps", "master-api", "src", "main", "kotlin", "com", "paycore", "master", "merchant", "domain", "InstitutionCodeFormat.kt"),
            "PAYMENT institution": ("apps", "payment-api", "src", "main", "kotlin", "com", "paycore", "payment", "payment", "adapter", "integration",
                                    "institution", "InstitutionCodeFormat.kt"),
            "PAYMENT entitlement": ("apps", "payment-api", "src", "main", "kotlin", "com", "paycore", "payment", "entitlement", "domain", "EntitlementSnapshot.kt"),
            "Simulator VAN": (*SIM_MAIN, "van", "adapter", "integration", "VanMessages.kt"),
            "Simulator registration": (*SIM_MAIN, "authentication", "domain", "RegistrationRequest.kt"),
        }
        for name, parts in definitions.items():
            text = read(*parts)
            self.assertIn('"' + PATTERN + '"', text, name)
            for old in ("[A-Z0-9-]{1,32}", "[A-Z0-9_]{1,32}"):
                self.assertNotIn(old, text, name)
        for name in ("AcquirerCode.kt", "InstitutionRefs.kt"):
            self.assertIn("InstitutionCodeFormat", read("apps", "master-api", "src", "main", "kotlin", "com", "paycore", "master", "merchant", "domain", name), name)

    def test_contract_golden_messages_carry_only_common_format_codes(self):
        for app in ("institution-simulator", "payment-api", "master-api"):
            for name, s in golden(app, "authentication-contract-v1.golden.json")["scenarios"].items():
                self.assertRegex(s["registerRequest"]["cardCompany"], CODE, name)
                for key in ("issuerCode", "acquirerCode", "providerCode"):
                    if key in s["lookup"]:
                        self.assertRegex(s["lookup"][key], CODE, f"{app} {name} {key}")
        for app in ("institution-simulator", "payment-api"):
            for contract in ("van-contract-sim-van-1.golden.json", "van-contract-sim-van-2.golden.json"):
                for name, m in golden(app, contract)["messages"].items():
                    if "routeRef" in m:
                        self.assertRegex(m["routeRef"], CODE, f"{contract} {name}")
                    for key, value in m.get("authentication", {}).items():
                        if key != "evidenceRef":
                            self.assertRegex(value, CODE, f"{contract} {name} {key}")
        for app, version in (("payment-api", 2), ("payment-api", 3), ("master-api", 3)):
            g = golden(app, f"entitlement-snapshot-v{version}.golden.json")
            routes = [r for a in json.loads(g["contentCanonical"])["accounts"] for r in a["cardRoutes"]]
            self.assertTrue(routes, f"{app} v{version}")
            for r in routes:
                self.assertRegex(r["acquirerCode"], CODE)
                self.assertRegex(r["routeRef"], CODE)


if __name__ == "__main__":
    unittest.main()
