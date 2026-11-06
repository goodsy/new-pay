package com.paycore.financial.acquiring.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * ACK·결과 계약 v1 엄격 해석(AR-F1..F4 — §5.1). 받는 쪽은 계약 이름만 고르고(점 임시명 무시), 문법·트레일러 hash·건수·합계 위반을 [AcquisitionContractViolation]으로
 * 거부한다(응답 서비스가 격리). 부분 다운로드(잘린 바이트)는 트레일러 hash 불일치·마지막 LF 누락으로 드러난다.
 */
class AcquirerResponseFormatSpec : FunSpec({

    val fileId = AcquisitionFileId.parse("ACQ.SIMACQA.TEST.20261105.001")
    val sha = "fd3ae1319bcd011286da952a8d9b33392ff044bbd1584b76fe7db934a8c0300f"
    val ack = AcquirerAck(fileId, 1, AckOutcome.RECEIVED, null, sha, 494, 2, Instant.parse("2026-11-05T14:06:01.250Z"))
    val result = AcquirerResultFile(
        fileId, sha, 1, Instant.parse("2026-11-05T14:06:31.250Z"),
        listOf(
            AcquirerResultLine(1, "ptx_auth_b", AcquisitionRecordType.SALE, LineResult.ACCEPTED, null, 15_000),
            AcquirerResultLine(2, "ptx_auth_a", AcquisitionRecordType.SALE, LineResult.REJECTED, "AMOUNT_MISMATCH", 30_000),
        ),
    )

    fun text(b: ByteArray) = String(b, Charsets.US_ASCII)
    fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)
    fun violates(block: () -> Unit) = shouldThrow<AcquisitionContractViolation> { block() }

    test("AR-F1 only contract names are responses: dot temp names, other kinds, bad sequences and other grammars are ignored (null)") {
        AcquirerResponseFormat.parseName("ACK.ACQ.SIMACQA.TEST.20261105.001.01.dat")?.kind shouldBe AcquirerResponseKind.ACK
        AcquirerResponseFormat.parseName("RES.ACQ.SIMACQA.TEST.20261105.001.99.dat")?.seq shouldBe 99
        listOf(
            ".ACK.ACQ.SIMACQA.TEST.20261105.001.01.dat.tmp", "ACK.ACQ.SIMACQA.TEST.20261105.001.00.dat", "ACK.ACQ.SIMACQA.TEST.20261105.001.1.dat",
            "NAK.ACQ.SIMACQA.TEST.20261105.001.01.dat", "ACK.ACQ.simacqa.TEST.20261105.001.01.dat", "ACK.ACQ.SIMACQA.TEST.20261131.001.01.dat",
            "ACK.ACQ.SIMACQA.TEST.20261105.001.01.dat.part", "ACQ.SIMACQA.TEST.20261105.001.dat",
        ).forEach { AcquirerResponseFormat.parseName(it) shouldBe null }
    }

    test("AR-F2 ACK: shape, outcome/reason/count coupling, hash form, timestamp form and canonical numbers are enforced") {
        AcquirerResponseFormat.parseAck(AcquirerResponseFormat.renderAck(ack)) shouldBe ack
        val good = text(AcquirerResponseFormat.renderAck(ack))
        val aLine = good.lineSequence().first()
        fun withA(a: String) = bytes("$a\nT|${AcquisitionFileFormat.sha256(bytes("$a\n"))}\n")
        listOf(
            aLine.replace("|RECEIVED||", "|RECEIVED|X|"), // RECEIVED에 사유
            aLine.replace("|494|2|", "|494||"), // RECEIVED에 건수 없음
            aLine.replace("|RECEIVED|", "|ACCEPTED|"), // 모르는 결과
            aLine.replace("|01|", "|1|"), // 순번 두 자리 아님
            aLine.replace("|494|", "|0494|"), // 앞 0
            aLine.replace(".250Z", ".25Z"), // 시각 형식
            aLine.replace(sha, sha.uppercase()), // hash 대문자
            "$aLine|extra", // 필드 수
            aLine.replaceFirst("A|1|", "A|2|"), // 형식 버전
        ).forEach { a -> violates { AcquirerResponseFormat.parseAck(withA(a)) } }
        // 값 객체 불변식: 거절 ACK에는 사유가 있어야 한다.
        violates { AcquirerAck(fileId, 1, AckOutcome.FILE_REJECTED, null, sha, 494, null, ack.receivedAt) }
    }

    test("AR-F3 partial downloads and transport damage are detected: truncated bytes, wrong trailer hash, CRLF, missing final LF, BOM, blank line") {
        val good = AcquirerResponseFormat.renderResult(result)
        listOf(
            good.copyOf(good.size - 5), // 잘림(마지막 LF 없음)
            bytes(text(good).replace("15000\n", "15001\n")), // 본문 바뀜 → bodySha256 불일치
            bytes(text(good).replace("\n", "\r\n")),
            good.copyOf(good.size - 1),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + good,
            bytes(text(good).replaceFirst("\n", "\n\n")),
            bytes(text(good).lines().filterIndexed { i, _ -> i != 2 }.joinToString("\n")), // 한 줄 빠짐(+ 건수·hash)
        ).forEach { b -> violates { AcquirerResponseFormat.parseResult(b) } }
    }

    test("AR-F4 result: header id/seq coupling, ascending lines, line result/reason coupling, totals and positive amounts are enforced") {
        AcquirerResponseFormat.parseResult(AcquirerResponseFormat.renderResult(result)) shouldBe result
        val good = text(AcquirerResponseFormat.renderResult(result))
        fun rehash(t: String): ByteArray {
            val body = t.lines().filter { it.isNotEmpty() }.dropLast(1).joinToString("") { "$it\n" }
            val trailer = t.lines().filter { it.isNotEmpty() }.last().split("|").dropLast(1).joinToString("|")
            return bytes(body + trailer + "|" + AcquisitionFileFormat.sha256(bytes(body)) + "\n")
        }
        listOf(
            good.replace("RES.ACQ.SIMACQA.TEST.20261105.001.01|", "RES.ACQ.SIMACQA.TEST.20261105.001.02|"), // 결과 파일 ID ≠ 회차
            good.replace("T|2|1|1|15000|0|", "T|2|2|0|15000|0|"), // 건수 불일치
            good.replace("T|2|1|1|15000|0|", "T|2|1|1|15001|0|"), // 합계 불일치
            good.replace("R|1|ptx_auth_b|SALE|ACCEPTED||15000", "R|1|ptx_auth_b|SALE|ACCEPTED|X|15000"), // ACCEPTED에 사유
            good.replace("R|1|ptx_auth_b|SALE|ACCEPTED||15000", "R|3|ptx_auth_b|SALE|ACCEPTED||15000"), // 순번 내림차순
            good.replace("|15000\n", "|0\n").replace("T|2|1|1|15000|", "T|2|1|1|0|"), // 금액 0
        ).forEach { t -> violates { AcquirerResponseFormat.parseResult(rehash(t)) } }
    }

    test("AR-F5 accepted totals that overflow a long are a contract violation, not an arithmetic crash (review n-3 — the poison file is quarantined)") {
        val head = "H|1|RES.ACQ.SIMACQA.TEST.20261105.001.01|ACQ.SIMACQA.TEST.20261105.001|$sha|01|2026-11-05T14:06:31.250Z\n" +
            "R|1|ptx_a|SALE|ACCEPTED||9000000000000000000\nR|2|ptx_b|SALE|ACCEPTED||9000000000000000000\n"
        val poison = bytes(head + "T|2|2|0|0|0|" + AcquisitionFileFormat.sha256(bytes(head)) + "\n")
        violates { AcquirerResponseFormat.parseResult(poison) }
    }
})
