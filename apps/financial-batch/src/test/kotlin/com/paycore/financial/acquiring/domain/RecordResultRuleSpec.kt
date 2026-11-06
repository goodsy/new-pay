package com.paycore.financial.acquiring.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * 레코드 결과 축(RR-1..RR-7 — append-only). 사용자 결정 D-ACQ-004(ACCEPTED = 매입 확정, REJECTED 종결, 마감 UNKNOWN)·D-ACQ-010(늦은 결과 자동 반영·UNKNOWN 보존·
 * 인정 시각 = 수신 시각·상충 격리)과 Implementer 해석 I-46(같음 = 결과·사유·출처)을 순수 domain으로 고정한다.
 */
class RecordResultRuleSpec : BehaviorSpec({

    val t1 = Instant.parse("2026-11-05T15:00:00Z")
    val t2 = Instant.parse("2026-11-06T15:00:00Z")
    val accepted = IncomingRecordResult(LineResult.ACCEPTED, null, RecordResultSource.RESULT_FILE)
    val rejected = IncomingRecordResult(LineResult.REJECTED, "AMOUNT_MISMATCH", RecordResultSource.RESULT_FILE)
    val fileRejected = IncomingRecordResult(LineResult.REJECTED, "BODY_HASH_MISMATCH", RecordResultSource.FILE_REJECTED)

    fun appended(entries: List<RecordResultEntry>, incoming: IncomingRecordResult, at: Instant) =
        (RecordResultRule.apply(entries, incoming, at) as RecordResultApplication.Append).entry

    Given("RR-1 a record without any result") {
        Then("its status is AWAITING and an ACCEPTED result appends entry 1 recognized at the receipt time (D-ACQ-010)") {
            RecordResultRule.status(emptyList()) shouldBe RecordStatus.AWAITING
            val e = appended(emptyList(), accepted, t1)
            listOf(e.entryNo, e.result, e.recognizedAt, e.source) shouldBe listOf(1, RecordResult.ACCEPTED, t1, RecordResultSource.RESULT_FILE)
            RecordResultRule.status(listOf(e)) shouldBe RecordStatus.ACCEPTED
        }
    }

    Given("RR-2 the same result arrives again (another result file, duplicate scenario)") {
        val first = appended(emptyList(), rejected, t1)
        Then("it is a duplicate - no new entry; a REJECTED with another reason is not the same (I-46 fail-closed)") {
            RecordResultRule.apply(listOf(first), rejected, t2) shouldBe RecordResultApplication.Duplicate
            RecordResultRule.apply(listOf(first), rejected.copy(reasonCode = "DUPLICATE_RECORD"), t2).shouldBeInstanceOf<RecordResultApplication.Conflict>()
        }
    }

    Given("RR-3 a different result for an already decided record (conflicting scenario)") {
        val first = appended(emptyList(), accepted, t1)
        val conflict = (RecordResultRule.apply(listOf(first), rejected, t2) as RecordResultApplication.Conflict).entry
        Then("a CONFLICT entry is appended (quarantine) carrying the incoming result; the first entry stays; later results are not written") {
            listOf(conflict.entryNo, conflict.result, conflict.observedResult, conflict.reasonCode) shouldBe listOf(2, RecordResult.CONFLICT, RecordResult.REJECTED, "AMOUNT_MISMATCH")
            RecordResultRule.status(listOf(first, conflict)) shouldBe RecordStatus.CONFLICT
            RecordResultRule.apply(listOf(first, conflict), accepted, t2) shouldBe RecordResultApplication.AlreadyConflicted
        }
    }

    Given("RR-4 the result deadline passes without a result, then the late result arrives") {
        val unknown = checkNotNull(RecordResultRule.closeAtDeadline(emptyList(), t1))
        val late = appended(listOf(unknown), accepted, t2)
        Then("UNKNOWN is entry 1 (DEADLINE), the late ACCEPTED is entry 2 recognized at its receipt time and the UNKNOWN entry is preserved (D-ACQ-010)") {
            listOf(unknown.result, unknown.source, RecordResultRule.status(listOf(unknown))) shouldBe listOf(RecordResult.UNKNOWN, RecordResultSource.DEADLINE, RecordStatus.UNKNOWN)
            listOf(late.entryNo, late.result, late.recognizedAt) shouldBe listOf(2, RecordResult.ACCEPTED, t2)
            RecordResultRule.status(listOf(unknown, late)) shouldBe RecordStatus.ACCEPTED
        }
        And("the deadline does not touch decided or already unknown records") {
            RecordResultRule.closeAtDeadline(listOf(unknown), t2) shouldBe null
            RecordResultRule.closeAtDeadline(listOf(late), t2) shouldBe null
        }
    }

    Given("RR-5 a whole-file rejection (ACK FILE_REJECTED) closes the records (D-ACQ-004, I-7)") {
        val e = appended(emptyList(), fileRejected, t1)
        Then("the status is FILE_REJECTED, a later result-file line is a conflict, and the CANCEL rule sees REJECTED (not acquired)") {
            RecordResultRule.status(listOf(e)) shouldBe RecordStatus.FILE_REJECTED
            RecordResultRule.apply(listOf(e), rejected, t2).shouldBeInstanceOf<RecordResultApplication.Conflict>()
            RecordResultRule.toOriginalSaleResult(RecordStatus.FILE_REJECTED) shouldBe OriginalSaleResult.REJECTED
        }
    }

    Given("RR-6 the file result summary") {
        Then("all decided (including conflicts) = complete; any awaiting or unknown = not complete; an empty file is complete") {
            RecordResultRule.allDecided(listOf(RecordStatus.ACCEPTED, RecordStatus.REJECTED, RecordStatus.CONFLICT, RecordStatus.FILE_REJECTED)) shouldBe true
            RecordResultRule.allDecided(listOf(RecordStatus.ACCEPTED, RecordStatus.UNKNOWN)) shouldBe false
            RecordResultRule.allDecided(listOf(RecordStatus.AWAITING)) shouldBe false
            RecordResultRule.allDecided(emptyList()) shouldBe true
            RecordStatus.entries.map(RecordResultRule::toOriginalSaleResult) shouldBe listOf(
                OriginalSaleResult.AWAITING, OriginalSaleResult.ACCEPTED, OriginalSaleResult.REJECTED, OriginalSaleResult.REJECTED, OriginalSaleResult.UNKNOWN,
                OriginalSaleResult.CONFLICT,
            )
        }
    }

    Given("RR-8 another generation of the same transaction and type is already ACCEPTED (D-ACQ-014 — one ACCEPTED across generations)") {
        Then("an ACCEPTED for this record becomes a CONFLICT entry (quarantine, observed ACCEPTED), also after UNKNOWN; a REJECTED is still recorded normally") {
            val c = (RecordResultRule.apply(emptyList(), accepted, t1, otherGenerationAccepted = true) as RecordResultApplication.Conflict).entry
            listOf(c.entryNo, c.result, c.observedResult, c.reasonCode) shouldBe listOf(1, RecordResult.CONFLICT, RecordResult.ACCEPTED, null)
            val unknown = checkNotNull(RecordResultRule.closeAtDeadline(emptyList(), t1))
            RecordResultRule.apply(listOf(unknown), accepted, t2, otherGenerationAccepted = true).shouldBeInstanceOf<RecordResultApplication.Conflict>()
            appended(emptyList(), rejected, t1).result shouldBe RecordResult.REJECTED
            RecordResultRule.apply(emptyList(), rejected, t1, otherGenerationAccepted = true).shouldBeInstanceOf<RecordResultApplication.Append>()
        }
    }

    Given("RR-7 impossible entries and inputs (programming errors)") {
        Then("shapes outside the table CHECK are rejected") {
            shouldThrow<AcquisitionContractViolation> { RecordResultEntry(1, RecordResult.ACCEPTED, null, "X", RecordResultSource.RESULT_FILE, t1) }
            shouldThrow<AcquisitionContractViolation> { RecordResultEntry(1, RecordResult.UNKNOWN, null, null, RecordResultSource.RESULT_FILE, t1) }
            shouldThrow<AcquisitionContractViolation> { RecordResultEntry(1, RecordResult.CONFLICT, null, null, RecordResultSource.RESULT_FILE, t1) }
            shouldThrow<AcquisitionContractViolation> { RecordResultEntry(0, RecordResult.ACCEPTED, null, null, RecordResultSource.RESULT_FILE, t1) }
            shouldThrow<AcquisitionContractViolation> { IncomingRecordResult(LineResult.ACCEPTED, null, RecordResultSource.FILE_REJECTED) }
            shouldThrow<AcquisitionContractViolation> { IncomingRecordResult(LineResult.ACCEPTED, null, RecordResultSource.DEADLINE) }
        }
    }
})
