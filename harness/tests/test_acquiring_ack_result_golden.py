"""매입 ACK·결과 파일 계약 v1 golden(ACQUIRING-001 §5·§7 rev 0.5 — 가상 매입사가 outbox에 게시하는 파일. 앱 코드와 독립 생성, 가상 규격이며 실제 카드사 규격 추측이 아니다).

가상 매입사(apps/institution-simulator `acquirer.domain.AcknowledgementFileV1`·`ResultFileV1` 렌더링)와 FINANCIAL(2-F에서 같은 파일을 읽는다)은 코드를 공유하지
않고 이 파일 하나로 계약을 맞춘다. 이 시험은 Python 표준 라이브러리(`hashlib`·`json`)만으로 파일 바이트를 만들어 앱 시험 자원의 golden과 비교한다.
2-E(2026-11-05)는 institution-simulator 자원만 대상이다 — financial-batch 자원은 2-F가 같은 파일을 더한다(이번 작업은 financial-batch를 쓰지 않는다).

계약 요점(사용자 결정 D-ACQ-004·010과 Implementer 해석 — ACQUIRING-001 §3 I-28~I-30):
- 공통: 구분자 `|`, US-ASCII, 줄 끝 LF, 마지막 줄 뒤 LF 하나, BOM·CR·빈 줄 없음, 빈 값 = 빈 문자열. 시각 = UTC yyyy-MM-ddTHH:mm:ss.SSSZ.
- 파일명: ACK = `ACK.{fileId}.{nn}.dat`, 결과 = `RES.{fileId}.{nn}.dat`(nn = 01..99 두 자리). 게시는 임시명(`.{name}.tmp`, 점으로 시작) → rename. 받는 쪽은 점으로 시작하는 이름을 무시한다.
- ACK(수신 — 레코드 결과와 별개, D-ACQ-004):
    A|1|fileId|ackSeq|outcome|reasonCode|receivedSha256|receivedBytes|recordCount|receivedAt   (10필드)
    T|bodySha256                                                                                (2필드 — A 줄 바이트(LF 포함)의 SHA-256)
  outcome = RECEIVED | FILE_REJECTED | RECEIPT_CONFLICT. reasonCode는 RECEIVED일 때만 빈 값, recordCount는 RECEIVED일 때만 있다. receivedSha256 = 받은 파일 전체
  바이트 SHA-256(FINANCIAL 봉인 hash와 대조). ackSeq = 그 파일 ID의 수신 순번(첫 수신 01, 같은 ID 다른 내용 수신마다 +1).
- 결과(레코드별 ACCEPTED/REJECTED — 여러 회차 파일로 나뉠 수 있다):
    H|1|resultFileId|fileId|fileSha256|resultSeq|producedAt                                    (7필드, resultFileId = RES.{fileId}.{nn})
    R|recordSeq|transactionId|SALE/CANCEL|ACCEPTED/REJECTED|reasonCode|recordAmount              (7필드, recordSeq 오름차순, 한 파일 안 중복 없음)
    T|lineCount|acceptedCount|rejectedCount|acceptedSaleTotal|acceptedCancelTotal|bodySha256    (7필드 — bodySha256 = H·R 줄 바이트의 SHA-256)
  reasonCode는 REJECTED일 때만 있다(`[A-Z0-9_]{1,32}` 가상 코드 — 업무 의미 해석 없이 기록·노출만). 0건 파일의 결과는 R 줄 없는 결과 파일 하나다.
값은 Demo(운영 ID·금액 아님). vector는 **독립 형식 vector**다 — 가상 매입사 판정 결과의 사본이 아니라 형식·바이트 계약 예시다(2-E 리뷰 n-5).
받은 파일 hash·크기는 매입 파일 golden(test_acquiring_file_golden.py)의 vector에서 계산한다.
`python3 harness/tests/test_acquiring_ack_result_golden.py --write`로 다시 쓴다(검토된 계약 변경일 때만).
"""
import hashlib
import json
import os
import sys
import unittest
from collections import OrderedDict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_acquiring_file_golden as file_golden  # noqa: E402 — 같은 디렉터리의 매입 파일 계약 생성기(받은 파일 바이트의 원천)

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
NAME = "acquiring-ack-result-v1.golden.json"
TARGETS = [os.path.join(ROOT, "apps", app, "src", "test", "resources", "contracts", NAME) for app in ("financial-batch", "institution-simulator")]

ACK_FIELDS = 10
ACK_TRAILER_FIELDS = 2
RESULT_HEADER_FIELDS = 7
RESULT_LINE_FIELDS = 7
RESULT_TRAILER_FIELDS = 7


def received(vector_name):
    for c in file_golden.cases():
        if c["name"] == vector_name:
            content, _ = file_golden.render(c["header"], c["records"])
            return c, content
    raise KeyError(vector_name)


def text(v):
    return "" if v is None else str(v)


def render_ack(a):
    line = "|".join(["A", "1", a["fileId"], "%02d" % a["ackSeq"], a["outcome"], text(a["reasonCode"]), a["receivedSha256"], str(a["receivedBytes"]),
                     text(a["recordCount"]), a["receivedAt"]]) + "\n"
    body = line.encode("ascii")
    return body + ("T|" + hashlib.sha256(body).hexdigest() + "\n").encode("ascii")


def render_result(r):
    result_file_id = "RES.%s.%02d" % (r["fileId"], r["resultSeq"])
    lines = ["|".join(["H", "1", result_file_id, r["fileId"], r["fileSha256"], "%02d" % r["resultSeq"], r["producedAt"]])]
    for x in r["lines"]:
        lines.append("|".join(["R", str(x["recordSeq"]), x["transactionId"], x["recordType"], x["result"], text(x["reasonCode"]), str(x["recordAmountMinor"])]))
    body = "".join(line + "\n" for line in lines).encode("ascii")
    accepted = [x for x in r["lines"] if x["result"] == "ACCEPTED"]
    trailer = "|".join(["T", str(len(r["lines"])), str(len(accepted)), str(len(r["lines"]) - len(accepted)),
                        str(sum(x["recordAmountMinor"] for x in accepted if x["recordType"] == "SALE")),
                        str(sum(x["recordAmountMinor"] for x in accepted if x["recordType"] == "CANCEL")),
                        hashlib.sha256(body).hexdigest()])
    return body + (trailer + "\n").encode("ascii"), result_file_id


def ack(name, vector_name, ack_seq, outcome, reason, received_at, sha_override=None):
    c, content = received(vector_name)
    return OrderedDict([
        ("name", name), ("fileId", c["header"]["fileId"]), ("ackSeq", ack_seq), ("outcome", outcome), ("reasonCode", reason),
        ("receivedSha256", sha_override or hashlib.sha256(content).hexdigest()), ("receivedBytes", len(content)),
        ("recordCount", len(c["records"]) if outcome == "RECEIVED" else None), ("receivedAt", received_at),
    ])


def line(record, result, reason=None):
    return OrderedDict([
        ("recordSeq", record["seq"]), ("transactionId", record["transactionId"]), ("recordType", record["type"]), ("result", result), ("reasonCode", reason),
        ("recordAmountMinor", record["recordAmountMinor"]),
    ])


def result(name, vector_name, result_seq, produced_at, decide):
    c, content = received(vector_name)
    return OrderedDict([
        ("name", name), ("fileId", c["header"]["fileId"]), ("fileSha256", hashlib.sha256(content).hexdigest()), ("resultSeq", result_seq),
        ("producedAt", produced_at), ("lines", [x for x in (decide(r) for r in c["records"]) if x is not None]),
    ])


def ack_cases():
    return [
        ack("received-regular", "regular-two-sales-one-netted", 1, "RECEIVED", None, "2026-11-05T14:06:01.250Z"),
        ack("file-rejected", "regular-two-sales-one-netted", 1, "FILE_REJECTED", "BODY_HASH_MISMATCH", "2026-11-05T14:06:01.250Z"),
        # 같은 파일 ID에 다른 내용이 들어왔다(두 번째 수신 — ackSeq 02, 받은 hash = 그 다른 내용의 hash).
        ack("receipt-conflict-second-receipt", "regular-two-sales-one-netted", 2, "RECEIPT_CONFLICT", "CONTENT_MISMATCH", "2026-11-06T00:00:00.000Z",
            sha_override=hashlib.sha256(b"different content").hexdigest()),
        ack("received-empty-day", "empty-business-day", 1, "RECEIVED", None, "2026-11-06T14:06:00.000Z"),
    ]


def result_cases():
    return [
        result("regular-one-accepted-one-rejected", "regular-two-sales-one-netted", 1, "2026-11-05T14:06:31.250Z",
               lambda r: line(r, "ACCEPTED") if r["seq"] == 1 else line(r, "REJECTED", "AMOUNT_MISMATCH")),
        # 결과 지연: 두 번째 회차에 남은 레코드 하나.
        result("regular-delayed-second-file", "regular-two-sales-one-netted", 2, "2026-11-05T15:06:31.250Z",
               lambda r: line(r, "ACCEPTED") if r["seq"] == 2 else None),
        result("empty-business-day", "empty-business-day", 1, "2026-11-06T14:06:30.000Z", lambda r: None),
        result("reacquisition-with-cancel", "reacquisition-version-with-cancel-record", 1, "2026-11-07T09:01:00.000Z", lambda r: line(r, "ACCEPTED")),
    ]


def vectors():
    acks = []
    for a in ack_cases():
        content = render_ack(a)
        name = "ACK.%s.%02d.dat" % (a["fileId"], a["ackSeq"])
        acks.append(OrderedDict(list(a.items()) + [("expected", OrderedDict([
            ("fileName", name), ("content", content.decode("ascii")), ("contentSha256", hashlib.sha256(content).hexdigest()), ("contentBytes", len(content)),
        ]))]))
    results = []
    for r in result_cases():
        content, result_file_id = render_result(r)
        results.append(OrderedDict(list(r.items()) + [("expected", OrderedDict([
            ("resultFileId", result_file_id), ("fileName", result_file_id + ".dat"), ("content", content.decode("ascii")),
            ("contentSha256", hashlib.sha256(content).hexdigest()), ("contentBytes", len(content)),
        ]))]))
    return acks, results


def golden_text():
    acks, results = vectors()
    golden = OrderedDict([
        ("description", "ACQUIRING-001 acknowledgement and result file contract v1 (virtual acquirer outbox format, generated independently of the apps with "
                        "Python hashlib/json; Demo identifiers and amounts, not production values)."),
        ("formatVersion", "1"),
        ("encoding", "US-ASCII, LF line ends, final LF, no BOM"),
        ("fieldCounts", OrderedDict([("A", ACK_FIELDS), ("ackT", ACK_TRAILER_FIELDS), ("H", RESULT_HEADER_FIELDS), ("R", RESULT_LINE_FIELDS),
                                     ("resultT", RESULT_TRAILER_FIELDS)])),
        ("acks", acks),
        ("results", results),
    ])
    return json.dumps(golden, indent=2, ensure_ascii=False) + "\n"


class AcquiringAckResultGolden(unittest.TestCase):
    def test_golden_file_of_the_simulator_equals_the_independent_generation(self):
        expected = golden_text()
        for path in TARGETS:
            with open(path, encoding="utf-8", newline="") as f:
                self.assertEqual(f.read(), expected, path)

    def test_lines_have_the_contract_field_counts_and_ascii_lf_only(self):
        acks, results = vectors()
        for v in acks + results:
            content = v["expected"]["content"]
            self.assertTrue(content.endswith("\n") and "\r" not in content and content.isascii() and "\n\n" not in content, v["name"])
        for a in acks:
            lines = a["expected"]["content"][:-1].split("\n")
            self.assertEqual([x.count("|") + 1 for x in lines], [ACK_FIELDS, ACK_TRAILER_FIELDS])
        for r in results:
            lines = r["expected"]["content"][:-1].split("\n")
            self.assertEqual(lines[0].count("|") + 1, RESULT_HEADER_FIELDS)
            self.assertTrue(all(x.count("|") + 1 == RESULT_LINE_FIELDS for x in lines[1:-1]))
            self.assertEqual(lines[-1].count("|") + 1, RESULT_TRAILER_FIELDS)

    def test_ack_shape_reason_and_count_follow_the_outcome(self):
        acks, _ = vectors()
        for a in acks:
            self.assertEqual(a["reasonCode"] is None, a["outcome"] == "RECEIVED", a["name"])
            self.assertEqual(a["recordCount"] is not None, a["outcome"] == "RECEIVED", a["name"])
            body, trailer = a["expected"]["content"].encode("ascii").split(b"\nT|")
            self.assertEqual(trailer.decode("ascii").rstrip("\n"), hashlib.sha256(body + b"\n").hexdigest())

    def test_result_trailer_totals_hashes_and_the_received_file_hash(self):
        _, results = vectors()
        for r in results:
            content = r["expected"]["content"].encode("ascii")
            trailer = content.decode("ascii")[:-1].split("\n")[-1].split("|")
            body = content[: len(content) - len("|".join(trailer)) - 1]
            self.assertEqual(trailer[-1], hashlib.sha256(body).hexdigest())
            self.assertEqual(int(trailer[1]), int(trailer[2]) + int(trailer[3]))
            self.assertEqual([x["recordSeq"] for x in r["lines"]], sorted({x["recordSeq"] for x in r["lines"]}))
            self.assertTrue(all((x["reasonCode"] is None) == (x["result"] == "ACCEPTED") for x in r["lines"]))
            _, received_content = received({
                "ACQ.SIMACQA.TEST.20261105.001": "regular-two-sales-one-netted", "ACQ.SIMACQA.TEST.20261106.001": "empty-business-day",
                "ACQ.SIMACQA.TEST.20261105.002": "reacquisition-version-with-cancel-record",
            }[r["fileId"]])
            self.assertEqual(r["fileSha256"], hashlib.sha256(received_content).hexdigest())


if __name__ == "__main__":
    if "--write" in sys.argv:
        for target in TARGETS:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(golden_text())
    else:
        unittest.main()
