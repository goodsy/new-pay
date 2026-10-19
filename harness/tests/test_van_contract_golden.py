"""가상 VAN TCP 계약 sim-van-1 golden(AUTH-001 §5.2·§11 — 두 앱 코드와 독립 생성).

PG(apps/payment-api, TCP Adapter)와 가상 VAN(apps/institution-simulator 모듈 van)은 Gradle 의존 없이 이 파일 하나로 계약을 공유한다. 이 시험은 Python 표준
라이브러리로 golden을 만들어 두 앱 시험 자원의 파일과 바이트 비교한다. Simulator 시험은 자기 프레임 codec이 golden 프레임과 같은 바이트를 만들고 응답이 golden
메시지와 같은 필드 집합임을, payment-api 시험은 golden을 따르는 시험 전용 stub VAN으로 Adapter를 구동한다(S4).

계약 요점(AUTH-001 §5.2):
- frame = length:u32be | version:u8(=1) | keyIdLen:u8 | keyId:ascii | mac:32 | payload(UTF-8 JSON). length = 이후 바이트 수(≤ 16 KiB).
  mac = HMAC-SHA256(key[keyId], version ‖ keyIdLen ‖ keyId ‖ payload). 아래 키는 **시험 전용**(운영 키 아님).
- 메시지는 첫 필드 `type`으로 구분하고 필드 집합은 닫혀 있다(모르는 필드·null·타입 오류 → FRAMING). 시각은 UTC ms, 금액은 minor 정수.
- payload hash(같은 요청 ID 재전송 판정, rev 0.3 m-7)는 업무 필드의 canonical 문자열 SHA-256이며 전송 필드(messageId·requestedAt)를 넣지 않는다.
- 값은 Demo(운영 MID·기관·키 아님). `python3 harness/tests/test_van_contract_golden.py --write`로 다시 쓴다(검토된 계약 변경일 때만).
"""
import base64
import hashlib
import hmac
import json
import os
import struct
import sys
import unittest
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
NAME = "van-contract-sim-van-1.golden.json"
TARGETS = [os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", NAME) for app in ("institution-simulator", "payment-api")]

CONTRACT = "sim-van-1"
VERSION = 1
MAX_LENGTH = 16 * 1024
KEY_ID = "sim-van-test-1"
KEY = b"test-only-sim-van-hmac-key-00001"  # 시험 전용 32바이트(운영 키 아님)
REQUEST_ID = "ar_01JA0000000000000000000001"
INQUIRY_ID = "aq_01JA0000000000000000000002"
DECISION_ID = "vd_01JA0000000000000000000003"
CLOSURE_ID = "vc_01JA0000000000000000000004"
PENDING_ID = "vp_01JA0000000000000000000005"


def od(*pairs):
    return OrderedDict(pairs)


def auth_request(message_id="01JA0000000000000000000010", requested_at="2026-10-16T01:00:31.000Z"):
    return od(
        ("type", "AUTH_REQUEST"), ("messageId", message_id), ("requestId", REQUEST_ID), ("institutionAccount", "SIMMERCH0001"),
        ("merchantRef", "mid-demo-0001"), ("environment", "TEST"), ("routeRef", "SIM-VAN-01"), ("amountMinor", 10000), ("currency", "KRW"),
        ("plan", od(("kind", "LUMP_SUM"))),
        ("authentication", od(("evidenceRef", "sim-ev-A"), ("providerCode", "SIMAUTH-1"), ("issuerCode", "SIMISS-A"), ("acquirerCode", "SIMACQ-A"))),
        ("requestedAt", requested_at),
    )


def approved_fields():
    return [("approvalNumber", "30000001"), ("providerTransactionId", "SIMPTX-0000000001"), ("approvedAmountMinor", 10000), ("currency", "KRW"),
            ("plan", od(("kind", "LUMP_SUM"))), ("cardType", "CREDIT"), ("institutionTransactionDate", "2026-10-16"), ("institutionBusinessDate", "2026-10-16")]


def auth_response(outcome, extra):
    return od(*([("type", "AUTH_RESPONSE"), ("inReplyTo", "01JA0000000000000000000010"), ("requestId", REQUEST_ID), ("outcome", outcome)] + extra +
                [("respondedAt", "2026-10-16T01:00:31.120Z")]))


def inquiry_request(close_if_absent):
    return od(("type", "INQUIRY_REQUEST"), ("messageId", "01JA0000000000000000000011"), ("inquiryId", INQUIRY_ID), ("originalRequestId", REQUEST_ID),
              ("institutionAccount", "SIMMERCH0001"), ("environment", "TEST"), ("closeIfAbsent", close_if_absent))


def inquiry_response(result, extra):
    return od(*([("type", "INQUIRY_RESPONSE"), ("inReplyTo", "01JA0000000000000000000011"), ("inquiryId", INQUIRY_ID), ("originalRequestId", REQUEST_ID),
                 ("result", result)] + extra + [("respondedAt", "2026-10-16T01:05:00.000Z")]))


def payload_bytes(message):
    return json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def frame(payload, key=KEY, key_id=KEY_ID):
    kid = key_id.encode("ascii")
    head = struct.pack(">BB", VERSION, len(kid)) + kid
    mac = hmac.new(key, head + payload, hashlib.sha256).digest()
    body = head + mac + payload
    return struct.pack(">I", len(body)) + body


def payload_hash(req):
    """업무 필드 canonical(전송 필드 messageId·requestedAt 제외). 필드 값에는 줄바꿈이 없다(필드 문법)."""
    plan = req["plan"]
    a = req["authentication"]
    fields = [req["requestId"], req["institutionAccount"], req["merchantRef"], req["environment"], req["routeRef"], str(req["amountMinor"]), req["currency"],
              plan["kind"], str(plan.get("months", "")), a["evidenceRef"], a["providerCode"], a["issuerCode"], a["acquirerCode"]]
    canonical = CONTRACT + "/AUTH_REQUEST\n" + "\n".join(fields)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def golden_text():
    request = auth_request()
    resent = auth_request(message_id="01JA0000000000000000000020", requested_at="2026-10-16T01:00:33.000Z")
    installment = auth_request()
    installment["plan"] = od(("kind", "INSTALLMENT"), ("months", 3))
    messages = od(
        ("authRequest", request),
        ("authRequestResent", resent),
        ("authRequestInstallment", installment),
        ("authResponseApproved", auth_response("APPROVED", [("decisionId", DECISION_ID)] + approved_fields())),
        ("authResponseDeclined", auth_response("DECLINED", [("decisionId", DECISION_ID), ("declineCategory", "ISSUER_DECLINED")])),
        ("authResponsePending", auth_response("PENDING", [("pendingId", PENDING_ID)])),
        ("authResponseRequestConflict", auth_response("REQUEST_CONFLICT", [])),
        ("authResponseRejectedClosed", auth_response("REJECTED_CLOSED", [])),
        ("inquiryRequest", inquiry_request(False)),
        ("inquiryRequestClose", inquiry_request(True)),
        ("inquiryResponseFound", inquiry_response("FOUND", [("decisionId", DECISION_ID), ("outcome", "APPROVED")] + approved_fields())),
        ("inquiryResponseFoundDeclined", inquiry_response("FOUND", [("decisionId", DECISION_ID), ("outcome", "DECLINED"), ("declineCategory", "AUTHENTICATION_INVALID")])),
        ("inquiryResponsePending", inquiry_response("PENDING", [("pendingId", PENDING_ID)])),
        ("inquiryResponseNotFound", inquiry_response("NOT_FOUND", [])),
        ("inquiryResponseNoEffectConfirmed", inquiry_response("NO_EFFECT_CONFIRMED", [("closureId", CLOSURE_ID)])),
        ("inquiryResponseUnavailable", inquiry_response("UNAVAILABLE", [])),
    )
    approved = messages["authResponseApproved"]
    malformed = od(
        ("unknownField", od(*(list(approved.items()) + [("extra", True)]))),
        ("nullDecisionId", od(*((k, (None if k == "decisionId" else v)) for k, v in approved.items()))),
        ("amountAsString", od(*((k, ("10000" if k == "approvedAmountMinor" else v)) for k, v in approved.items()))),
        ("missingRespondedAt", od(*((k, v) for k, v in approved.items() if k != "respondedAt"))),
        ("approvedWithoutTerms", od(*((k, v) for k, v in approved.items() if k not in ("approvalNumber", "providerTransactionId")))),
    )
    frames = od(
        ("authRequest", frame(payload_bytes(request)).hex()),
        ("authResponseApproved", frame(payload_bytes(approved)).hex()),
        ("authResponseApprovedWrongKey", frame(payload_bytes(approved), key=b"another-test-only-key-0000000002").hex()),
    )
    golden = od(
        ("description", "AUTH-001 virtual VAN TCP contract sim-van-1 (generated independently of both apps; Demo values and a test-only HMAC key, "
                        "not production). A frame carries one message; the payload hash excludes transport fields."),
        ("contractVersion", CONTRACT),
        ("frameVersion", VERSION),
        ("maxFrameLength", MAX_LENGTH),
        ("hmac", od(("keyId", KEY_ID), ("keyBase64", base64.b64encode(KEY).decode("ascii")))),
        ("messages", messages),
        ("payloadHashes", od(("authRequest", payload_hash(request)), ("authRequestResent", payload_hash(resent)), ("authRequestInstallment", payload_hash(installment)))),
        ("frames", frames),
        ("malformedAuthResponses", malformed),
    )
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


class VanContractGolden(unittest.TestCase):
    def test_golden_files_of_both_apps_equal_the_independent_generation(self):
        expected = golden_text()
        for path in TARGETS:
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, path)

    def test_resent_request_has_the_same_payload_hash_and_a_business_change_does_not(self):
        g = json.loads(golden_text())
        self.assertEqual(g["payloadHashes"]["authRequest"], g["payloadHashes"]["authRequestResent"])
        self.assertNotEqual(g["payloadHashes"]["authRequest"], g["payloadHashes"]["authRequestInstallment"])
        self.assertNotEqual(g["messages"]["authRequest"]["messageId"], g["messages"]["authRequestResent"]["messageId"])

    def test_frame_layout_and_mac(self):
        g = json.loads(golden_text())
        raw = bytes.fromhex(g["frames"]["authRequest"])
        (length,) = struct.unpack(">I", raw[:4])
        self.assertEqual(length, len(raw) - 4)
        self.assertLessEqual(length, MAX_LENGTH)
        self.assertEqual(raw[4], VERSION)
        kid_len = raw[5]
        kid = raw[6:6 + kid_len]
        self.assertEqual(kid.decode("ascii"), KEY_ID)
        mac = raw[6 + kid_len:6 + kid_len + 32]
        payload = raw[6 + kid_len + 32:]
        self.assertEqual(json.loads(payload.decode("utf-8")), g["messages"]["authRequest"])
        self.assertTrue(hmac.compare_digest(mac, hmac.new(KEY, raw[4:6 + kid_len] + payload, hashlib.sha256).digest()))
        wrong = bytes.fromhex(g["frames"]["authResponseApprovedWrongKey"])
        good = bytes.fromhex(g["frames"]["authResponseApproved"])
        self.assertEqual(wrong[:6 + kid_len], good[:6 + kid_len])
        self.assertNotEqual(wrong[6 + kid_len:6 + kid_len + 32], good[6 + kid_len:6 + kid_len + 32])

    def test_every_message_starts_with_its_type_and_has_no_null(self):
        def no_null(v):
            if isinstance(v, dict):
                return all(no_null(x) for x in v.values())
            return v is not None
        for name, m in json.loads(golden_text())["messages"].items():
            self.assertEqual(list(m.keys())[0], "type", name)
            self.assertTrue(no_null(m), name)


if __name__ == "__main__":
    if "--write" in sys.argv:
        for target in TARGETS:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(golden_text())
    else:
        unittest.main()
