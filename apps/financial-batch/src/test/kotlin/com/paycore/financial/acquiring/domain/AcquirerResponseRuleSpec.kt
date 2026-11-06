package com.paycore.financial.acquiring.domain

import com.paycore.financial.acquiring.domain.AcquisitionFixture.CUTOFF
import com.paycore.financial.acquiring.domain.AcquisitionFixture.header
import com.paycore.financial.acquiring.domain.AcquisitionFixture.included
import com.paycore.financial.acquiring.domain.AcquisitionFixture.sale
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * 응답 대조(RS-1..RS-7 — §4.1·§5.1). 수신 축 전이(D-ACQ-004 — RECEIVED는 매입 확정 아님), hash 불일치 격리(I-8), ACK 없는 결과 = 수신 근거(I-8 RESULT_IMPLIED),
 * hash 일치 응답 = 전송 완료 근거(I-9 ACK_HASH_MATCH), 격리 요약 RECEIPT_CONFLICT(I-45), 결과 축 전이를 순수 domain으로 고정한다.
 */
class AcquirerResponseRuleSpec : BehaviorSpec({

    val (sealed, rendered) = AcquisitionFile.seal(header(), CUTOFF, listOf(included(sale("ptx_a", 10_000)), included(sale("ptx_b", 20_000))))
    val records = rendered.records
    val now = Instant.parse("2026-11-05T15:00:00Z")
    fun transmitted(): AcquisitionFile {
        val claimed = (sealed.claimTransmission(now, now.plusSeconds(60)) as TransmissionClaim.Claimed).file
        return claimed.completeTransmission(TransmissionOutcome.Delivered(TransmissionEvidence.RENAMED_AND_VERIFIED), TransmissionState.SEALED, now)
    }
    fun ack(outcome: AckOutcome, sha: String = sealed.contentSha256, bytes: Long = sealed.contentBytes.toLong(), count: Int? = 2, reason: String? = null, seq: Int = 1) =
        AcquirerAck(sealed.id, seq, outcome, reason, sha, bytes, if (outcome == AckOutcome.RECEIVED) count else null, now)
    fun result(lines: List<AcquirerResultLine>, sha: String = sealed.contentSha256) = AcquirerResultFile(sealed.id, sha, 1, now, lines)
    fun line(seq: Int, txn: String, amount: Long, r: LineResult = LineResult.ACCEPTED) =
        AcquirerResultLine(seq, txn, AcquisitionRecordType.SALE, r, if (r == LineResult.REJECTED) "AMOUNT_MISMATCH" else null, amount)

    Given("RS-1 a RECEIVED ack whose received hash, size and record count match the sealed file") {
        val j = AcquirerResponseRule.judgeAck(transmitted(), ack(AckOutcome.RECEIVED))
        Then("it is accepted: receipt RECEIVED with ACK_HASH_MATCH evidence; the result axis does not move (RECEIVED is not acquisition confirmation)") {
            j shouldBe ResponseJudgement.Accept(ReceiptState.RECEIVED, ResponseEvidence.ACK_HASH_MATCH, null)
            val next = transmitted().recordResponse(ReceiptState.RECEIVED, true, now)
            listOf(next.receiptState, next.resultState, next.transmissionState) shouldBe listOf(ReceiptState.RECEIVED, ResultState.AWAITING, TransmissionState.TRANSMITTED)
        }
    }

    Given("RS-2 acks that disagree with the sealed file (I-8 — same id, other content)") {
        Then("hash, size or record count mismatch quarantine with RECEIPT_CONFLICT; a RECEIPT_CONFLICT ack is quarantined too") {
            listOf(
                ack(AckOutcome.RECEIVED, sha = "0".repeat(64)), ack(AckOutcome.RECEIVED, bytes = 1), ack(AckOutcome.RECEIVED, count = 3),
                ack(AckOutcome.RECEIPT_CONFLICT, sha = "1".repeat(64), reason = "CONTENT_MISMATCH", seq = 2),
            ).map { (AcquirerResponseRule.judgeAck(transmitted(), it) as ResponseJudgement.Quarantine).let { q -> q.reason to q.receiptAfter } } shouldBe listOf(
                QuarantineReason.HASH_MISMATCH to ReceiptState.RECEIPT_CONFLICT, QuarantineReason.HASH_MISMATCH to ReceiptState.RECEIPT_CONFLICT,
                QuarantineReason.COUNT_MISMATCH to ReceiptState.RECEIPT_CONFLICT, QuarantineReason.RECEIPT_CONFLICT to ReceiptState.RECEIPT_CONFLICT,
            )
        }
    }

    Given("RS-3 a FILE_REJECTED ack (D-ACQ-004 — the file's records are closed)") {
        Then("it is accepted with the rejection reason; a contradicting ack afterwards is quarantined (ACK_CONFLICT), and later acks stay quarantined (AFTER_CONFLICT)") {
            AcquirerResponseRule.judgeAck(transmitted(), ack(AckOutcome.FILE_REJECTED, reason = "BODY_HASH_MISMATCH")) shouldBe
                ResponseJudgement.Accept(ReceiptState.FILE_REJECTED, ResponseEvidence.ACK_HASH_MATCH, "BODY_HASH_MISMATCH")
            val rejected = transmitted().recordResponse(ReceiptState.FILE_REJECTED, true, now)
            AcquirerResponseRule.judgeAck(rejected, ack(AckOutcome.RECEIVED)) shouldBe ResponseJudgement.Quarantine(QuarantineReason.ACK_CONFLICT, ReceiptState.RECEIPT_CONFLICT)
            val conflicted = rejected.recordResponse(ReceiptState.RECEIPT_CONFLICT, false, now)
            AcquirerResponseRule.judgeAck(conflicted, ack(AckOutcome.RECEIVED)) shouldBe ResponseJudgement.Quarantine(QuarantineReason.AFTER_CONFLICT, ReceiptState.RECEIPT_CONFLICT)
        }
    }

    Given("RS-4 a result file without any ack (ack lost — I-8 RESULT_IMPLIED)") {
        val j = AcquirerResponseRule.judgeResult(transmitted(), records, result(listOf(line(1, records[0].transactionId, records[0].recordAmountMinor))))
        Then("it is the receipt evidence: receipt NONE → RECEIVED with RESULT_HASH_MATCH") {
            j shouldBe ResponseJudgement.Accept(ReceiptState.RECEIVED, ResponseEvidence.RESULT_HASH_MATCH, null)
        }
    }

    Given("RS-5 result files that disagree with the sealed file") {
        Then("other hash → RECEIPT_CONFLICT quarantine; a line whose seq, transaction, type or amount differs → RECORD_MISMATCH (receipt unchanged)") {
            AcquirerResponseRule.judgeResult(transmitted(), records, result(emptyList(), sha = "2".repeat(64))) shouldBe
                ResponseJudgement.Quarantine(QuarantineReason.HASH_MISMATCH, ReceiptState.RECEIPT_CONFLICT)
            listOf(line(3, records[0].transactionId, 1), line(1, "ptx_other", records[0].recordAmountMinor), line(1, records[0].transactionId, 1)).forEach {
                AcquirerResponseRule.judgeResult(transmitted(), records, result(listOf(it))) shouldBe ResponseJudgement.Quarantine(QuarantineReason.RECORD_MISMATCH, ReceiptState.NONE)
            }
        }
    }

    Given("RS-6 an ack or result with matching hash while the transmission axis says the outcome was unknown (I-9 ACK_HASH_MATCH)") {
        val claimed = (sealed.claimTransmission(now, now.plusSeconds(60)) as TransmissionClaim.Claimed).file
        val unknown = claimed.completeTransmission(TransmissionOutcome.Unknown(TransmissionFailureReason.RENAME_OUTCOME_UNKNOWN), TransmissionState.SEALED, now)
        Then("the file becomes TRANSMITTED at the response time; a quarantined response does not promote; a transmitting file is deferred (refused here)") {
            unknown.recordResponse(ReceiptState.RECEIVED, true, now).let { listOf(it.transmissionState, it.transmittedAt) } shouldBe listOf(TransmissionState.TRANSMITTED, now)
            unknown.recordResponse(ReceiptState.RECEIPT_CONFLICT, false, now).transmissionState shouldBe TransmissionState.TRANSMISSION_UNKNOWN
            shouldThrow<AcquisitionContractViolation> { claimed.recordResponse(ReceiptState.RECEIVED, true, now) }
        }
    }

    Given("RS-7 receipt and result axis transitions") {
        val received = transmitted().recordResponse(ReceiptState.RECEIVED, true, now)
        Then("receipt cannot go back to NONE or from RECEIVED to FILE_REJECTED; results go AWAITING → DEADLINE_PASSED → COMPLETE, COMPLETE is final") {
            shouldThrow<AcquisitionContractViolation> { received.recordResponse(ReceiptState.NONE, false, now) }
            shouldThrow<AcquisitionContractViolation> { received.recordResponse(ReceiptState.FILE_REJECTED, true, now) }
            val passed = received.withResultState(ResultState.DEADLINE_PASSED)
            val complete = passed.withResultState(ResultState.COMPLETE)
            complete.resultState shouldBe ResultState.COMPLETE
            shouldThrow<AcquisitionContractViolation> { complete.withResultState(ResultState.AWAITING) }
            shouldThrow<AcquisitionContractViolation> { complete.withResultState(ResultState.DEADLINE_PASSED) }
            shouldThrow<AcquisitionContractViolation> { passed.withResultState(ResultState.AWAITING) }
        }
    }

    Given("RS-8 a hash-matching ack and result for a file whose transmission axis is quarantined (other bytes under the id — review m-2)") {
        val claimed = (sealed.claimTransmission(now, now.plusSeconds(60)) as TransmissionClaim.Claimed).file
        val quarantined = claimed.completeTransmission(TransmissionOutcome.ContentMismatch, TransmissionState.SEALED, now)
        Then("both are quarantined TRANSMISSION_QUARANTINED with the receipt axis unchanged, and the file refuses any axis change from a response") {
            AcquirerResponseRule.judgeAck(quarantined, ack(AckOutcome.RECEIVED)) shouldBe
                ResponseJudgement.Quarantine(QuarantineReason.TRANSMISSION_QUARANTINED, ReceiptState.NONE)
            AcquirerResponseRule.judgeResult(quarantined, records, result(listOf(line(1, records[0].transactionId, records[0].recordAmountMinor)))) shouldBe
                ResponseJudgement.Quarantine(QuarantineReason.TRANSMISSION_QUARANTINED, ReceiptState.NONE)
            quarantined.recordResponse(ReceiptState.NONE, false, now).transmissionState shouldBe TransmissionState.TRANSMISSION_QUARANTINED
            shouldThrow<AcquisitionContractViolation> { quarantined.recordResponse(ReceiptState.RECEIVED, true, now) }
        }
    }
})
