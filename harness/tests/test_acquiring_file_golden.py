"""매입 파일 계약 v1 golden(ACQUIRING-001 §5 — FINANCIAL 앱 코드와 독립 생성, 가상 규격이며 실제 카드사 규격 추측이 아니다).

FINANCIAL(apps/financial-batch `acquiring.domain.AcquisitionFileFormat` 렌더링)과 가상 매입사(apps/institution-simulator — 배치2에서 같은 파일을 읽어 대조)는
코드를 공유하지 않고 이 파일 하나로 계약을 맞춘다. 이 시험은 Python 표준 라이브러리(`hashlib`·`json`)만으로 파일 바이트를 만들어 앱 시험 자원의 golden과 비교한다.
배치1(2026-11-05)은 financial-batch 자원만 대상이었고, 배치2 2-E가 Simulator 자원(같은 바이트 사본)을 더했다.

계약 요점(사용자 결정 D-ACQ-002·003·008·012와 Implementer 해석 I-15):
- 줄 `H`(헤더 11필드)·`D`(레코드 21필드)·`T`(트레일러 8필드), 구분자 `|`, US-ASCII, 줄 끝 LF, 마지막 줄 뒤 LF 하나, BOM·CR 없음, 빈 값 = 빈 문자열.
- H: H|1|fileId|senderId|acquirerCode|environment|yyyy-MM-dd|vvv|originalFileId|sealedAt|cutoffPolicyVersion
- D: D|seq|SALE/CANCEL|transactionId|originalTransactionId|merchantRef|approvalNumber|providerTransactionId|originalProviderTransactionId|
     institutionTransactionDate|eventAt|KRW|approvedAmount|nettedCancelAmount|recordAmount|planKind|installmentMonths|cardType|issuerCode|generation|reacquisitionOf
- T: T|recordCount|saleCount|saleAmountTotal|cancelCount|cancelAmountTotal|netAmount|bodySha256 — bodySha256 = SHA-256(H 줄 + D 줄 바이트, 각 LF 포함) 소문자 hex.
- fileId = ACQ.{acquirerCode}.{environment}.{yyyyMMdd}.{vvv}. 금액 = 부호 없는 10진 정수(KRW minor), 트레일러 netAmount만 음수 가능. 시각 = UTC yyyy-MM-ddTHH:mm:ss.SSSZ.
- SALE: recordAmount = approvedAmount − nettedCancelAmount(D-ACQ-002 상계). CANCEL: 원거래 참조 필수, 상계 없음. merchantRef = PayCore MID(D-ACQ-008 Demo).
- 봉인 hash(contentSha256) = 파일 전체 바이트 SHA-256.
값은 Demo(운영 ID·금액 아님). `python3 harness/tests/test_acquiring_file_golden.py --write`로 다시 쓴다(검토된 계약 변경일 때만).
"""
import hashlib
import json
import os
import sys
import unittest
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
NAME = "acquiring-file-v1.golden.json"
# 2-E(2026-11-05): 가상 매입사(institution-simulator)의 파서도 같은 golden을 읽는다(relay golden 관례 — 앱마다 같은 바이트 사본, 이 시험이 둘 다 대조).
TARGETS = [os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", NAME) for app in ("financial-batch", "institution-simulator")]

HEADER_FIELDS = 11
RECORD_FIELDS = 21
TRAILER_FIELDS = 8


def record(seq, rtype, txn, approved, netted, amount, event_at, original=None, original_ptx=None, plan="LUMP_SUM", months=None, card="CREDIT",
           generation=0, reacquisition_of=None):
    return OrderedDict([
        ("seq", seq), ("type", rtype), ("transactionId", txn), ("originalTransactionId", original), ("merchantRef", "demo-mid-001"),
        ("approvalNumber", "30000001"), ("providerTransactionId", "VAN-T-" + txn), ("originalProviderTransactionId", original_ptx),
        ("institutionTransactionDate", "2026-11-05"), ("eventAt", event_at), ("currency", "KRW"), ("approvedAmountMinor", approved),
        ("nettedCancelAmountMinor", netted), ("recordAmountMinor", amount), ("planKind", plan), ("installmentMonths", months), ("cardType", card),
        ("issuerCode", "SIMISSA"), ("generation", generation), ("reacquisitionOf", reacquisition_of),
    ])


def header(file_id, sealed_at, original_file_id=None):
    return OrderedDict([
        ("fileId", file_id), ("senderId", "PAYCORE"), ("sealedAt", sealed_at), ("cutoffPolicyVersion", "acq-cutoff-demo-1"), ("originalFileId", original_file_id),
    ])


def cases():
    return [
        OrderedDict([
            ("name", "regular-two-sales-one-netted"),
            ("header", header("ACQ.SIMACQA.TEST.20261105.001", "2026-11-05T14:05:00.000Z")),
            ("records", [
                record(1, "SALE", "ptx_auth_b", 20000, 5000, 15000, "2026-11-05T01:00:00.000Z"),
                record(2, "SALE", "ptx_auth_a", 30000, 0, 30000, "2026-11-05T03:04:05.678Z", plan="INSTALLMENT", months=3),
            ]),
        ]),
        OrderedDict([
            ("name", "empty-business-day"),
            ("header", header("ACQ.SIMACQA.TEST.20261106.001", "2026-11-06T14:05:00.000Z")),
            ("records", []),
        ]),
        # 배치2 형태(CANCEL 레코드 — D-ACQ-009, 재매입 버전 — D-ACQ-011)도 계약에 미리 고정한다. 배치1 봉인은 이 형태를 만들지 않는다.
        OrderedDict([
            ("name", "reacquisition-version-with-cancel-record"),
            ("header", header("ACQ.SIMACQA.TEST.20261105.002", "2026-11-07T09:00:00.000Z", "ACQ.SIMACQA.TEST.20261105.001")),
            ("records", [
                record(1, "SALE", "ptx_auth_c", 50000, 0, 50000, "2026-11-05T02:00:00.000Z", generation=1, reacquisition_of="ACQ.SIMACQA.TEST.20261105.001#3"),
                record(2, "CANCEL", "ptx_cancel_d", 40000, 0, 60000 - 50000, "2026-11-06T05:06:07.890Z", original="ptx_auth_d", original_ptx="VAN-T-ptx_auth_d",
                       card="CHECK"),
            ]),
        ]),
    ]


def text(v):
    return "" if v is None else str(v)


def parse_file_id(file_id):
    parts = file_id.split(".")
    assert len(parts) == 5 and parts[0] == "ACQ", file_id
    _, acquirer, environment, ymd, version = parts
    return acquirer, environment, ymd[0:4] + "-" + ymd[4:6] + "-" + ymd[6:8], version


def render(h, records):
    acquirer, environment, business_date, version = parse_file_id(h["fileId"])
    lines = ["|".join(["H", "1", h["fileId"], h["senderId"], acquirer, environment, business_date, version, text(h["originalFileId"]), h["sealedAt"],
                       h["cutoffPolicyVersion"]])]
    for r in records:
        lines.append("|".join([
            "D", str(r["seq"]), r["type"], r["transactionId"], text(r["originalTransactionId"]), r["merchantRef"], r["approvalNumber"], r["providerTransactionId"],
            text(r["originalProviderTransactionId"]), r["institutionTransactionDate"], r["eventAt"], r["currency"], str(r["approvedAmountMinor"]),
            str(r["nettedCancelAmountMinor"]), str(r["recordAmountMinor"]), r["planKind"], text(r["installmentMonths"]), r["cardType"], r["issuerCode"],
            str(r["generation"]), text(r["reacquisitionOf"]),
        ]))
    body = "".join(line + "\n" for line in lines).encode("ascii")
    sales = [r for r in records if r["type"] == "SALE"]
    cancels = [r for r in records if r["type"] == "CANCEL"]
    sale_total = sum(r["recordAmountMinor"] for r in sales)
    cancel_total = sum(r["recordAmountMinor"] for r in cancels)
    body_sha = hashlib.sha256(body).hexdigest()
    trailer = "|".join(["T", str(len(records)), str(len(sales)), str(sale_total), str(len(cancels)), str(cancel_total), str(sale_total - cancel_total), body_sha])
    content = body + (trailer + "\n").encode("ascii")
    return content, body_sha


def vectors():
    out = []
    for c in cases():
        content, body_sha = render(c["header"], c["records"])
        out.append(OrderedDict([
            ("name", c["name"]), ("header", c["header"]), ("records", c["records"]),
            ("expected", OrderedDict([
                ("content", content.decode("ascii")), ("bodySha256", body_sha), ("contentSha256", hashlib.sha256(content).hexdigest()),
                ("contentBytes", len(content)),
            ])),
        ]))
    return out


def golden_text():
    golden = OrderedDict([
        ("description", "ACQUIRING-001 acquisition file contract v1 (virtual acquirer format, generated independently of the apps with Python hashlib/json; "
                        "Demo identifiers and amounts, not production values)."),
        ("formatVersion", "1"),
        ("encoding", "US-ASCII, LF line ends, final LF, no BOM"),
        ("fieldCounts", OrderedDict([("H", HEADER_FIELDS), ("D", RECORD_FIELDS), ("T", TRAILER_FIELDS)])),
        ("vectors", vectors()),
    ])
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


class AcquiringFileGolden(unittest.TestCase):
    def test_golden_file_of_financial_batch_equals_the_independent_generation(self):
        expected = golden_text()
        for path in TARGETS:
            with open(path, encoding="utf-8", newline="") as f:
                self.assertEqual(f.read(), expected, path)

    def test_lines_have_the_contract_field_counts_and_ascii_lf_only(self):
        for v in vectors():
            content = v["expected"]["content"]
            self.assertTrue(content.endswith("\n") and "\r" not in content and content.isascii(), v["name"])
            lines = content[:-1].split("\n")
            self.assertEqual(lines[0].count("|") + 1, HEADER_FIELDS)
            self.assertTrue(all(line.count("|") + 1 == RECORD_FIELDS for line in lines[1:-1]))
            self.assertEqual(lines[-1].count("|") + 1, TRAILER_FIELDS)

    def test_sale_amounts_are_net_of_netted_cancels_and_cancels_refer_to_their_original(self):
        for v in vectors():
            for r in v["records"]:
                if r["type"] == "SALE":
                    self.assertEqual(r["recordAmountMinor"], r["approvedAmountMinor"] - r["nettedCancelAmountMinor"], v["name"])
                    self.assertIsNone(r["originalTransactionId"])
                else:
                    self.assertEqual(r["nettedCancelAmountMinor"], 0)
                    self.assertTrue(r["originalTransactionId"] and r["originalProviderTransactionId"])
                self.assertEqual(r["generation"] == 0, r["reacquisitionOf"] is None)

    def test_trailer_totals_and_hashes_are_consistent(self):
        for v in vectors():
            content = v["expected"]["content"].encode("ascii")
            trailer = content.decode("ascii")[:-1].split("\n")[-1].split("|")
            body = content[: len(content) - len("|".join(trailer)) - 1]
            self.assertEqual(trailer[-1], hashlib.sha256(body).hexdigest())
            self.assertEqual(int(trailer[6]), int(trailer[3]) - int(trailer[5]))
            self.assertEqual(v["expected"]["contentSha256"], hashlib.sha256(content).hexdigest())


if __name__ == "__main__":
    if "--write" in sys.argv:
        for target in TARGETS:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(golden_text())
    else:
        unittest.main()
