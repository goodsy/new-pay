package com.paycore.financial.acquiring.application

import com.paycore.financial.acquiring.application.provided.AcquisitionAttention
import com.paycore.financial.acquiring.application.provided.AcquisitionFileCursor
import com.paycore.financial.acquiring.application.provided.AcquisitionFileSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionFileSummary
import com.paycore.financial.acquiring.application.provided.AcquisitionHistory
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordStatusCounts
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordView
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquisitionFileDetailRows
import com.paycore.financial.acquiring.application.required.AcquisitionFileViewReader
import com.paycore.financial.acquiring.application.required.AcquisitionResultPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * ACQ-VIEW-001 §4.4·I-4·I-5·I-6: 조회 Use Case(Reader 대역 — DB 없이). "확인 필요" 사유 8종이 각각 어떤 저장 상태에서 나오는지(명시 Fixture — 정상 파일에는 없다),
 * RESULT_OVERDUE 기준점 = 지금 − 결과 마감(설정 없으면 계산하지 않음 — fail-closed), 재매입 검토 표시, keyset 다음 쪽 판정(limit + 1행), 조건 불변식.
 */
class AcquisitionFileViewServiceSpec : BehaviorSpec({

    val now = Instant.parse("2026-11-06T03:00:00Z")
    val day = LocalDate.parse("2026-11-05")

    /** 정상 종결 파일: 전송 완료·수신·결과 완료, 레코드 모두 ACCEPTED. 각 사유는 이 파일에서 한 가지만 바꾼 Named Fixture로 만든다. */
    fun completed(fileId: String = "ACQ.SIMACQA.TEST.20261105.001") = AcquisitionFileSummary(
        fileId = fileId, acquirerCode = "SIMACQA", environment = "TEST", businessDate = day, fileVersion = 1, originalFileId = null,
        recordCount = 2, saleCount = 2, saleAmountMinor = 30_000, cancelCount = 0, cancelAmountMinor = 0, netAmountMinor = 30_000,
        transmissionState = "TRANSMITTED", receiptState = "RECEIVED", resultState = "COMPLETE", sealedAt = Instant.parse("2026-11-05T14:05:00Z"),
        transmittedAt = Instant.parse("2026-11-05T14:06:00Z"), recordStatusCounts = AcquisitionRecordStatusCounts(accepted = 2), quarantinedResponses = 0,
    )

    class FakeReader(var summaries: List<AcquisitionFileSummary> = emptyList(), var detailRows: AcquisitionFileDetailRows? = null, var recordRows: List<AcquisitionRecordView>? = null) :
        AcquisitionFileViewReader {
        val calls = mutableListOf<Triple<String, Any?, Int>>()

        override fun search(criteria: AcquisitionFileSearchCriteria, overdueBefore: Instant?, rows: Int): List<AcquisitionFileSummary> {
            calls += Triple("search", overdueBefore, rows)
            return summaries.take(rows)
        }

        override fun detail(fileId: String, records: AcquisitionRecordSearchCriteria, recordRows: Int): AcquisitionFileDetailRows? {
            calls += Triple("detail", records, recordRows)
            return detailRows
        }

        override fun records(fileId: String, criteria: AcquisitionRecordSearchCriteria, rows: Int): List<AcquisitionRecordView>? {
            calls += Triple("records", criteria, rows)
            return recordRows?.take(rows)
        }
    }

    fun service(reader: AcquisitionFileViewReader, deadline: Duration? = Duration.ofHours(1)) = AcquisitionFileViewService(
        reader,
        object : AcquisitionResultPolicy {
            override val resultDeadline: Duration? = deadline
        },
        object : AcquiringClock {
            override fun now(): Instant = now
        },
    )

    fun record(seq: Int, status: String = "ACCEPTED") = AcquisitionRecordView(
        seq, "SALE", "ptx_$seq", null, "mid_demo", day, Instant.parse("2026-11-05T01:02:03.456Z"), "KRW", 10_000, 0, 10_000, 0, null, status, emptyList(),
    )

    val overdueBefore = now.minus(Duration.ofHours(1))

    Given("the attention reasons (§4.4) — one stored difference each") {
        Then("a completed file with every record ACCEPTED has none and is not a re-acquisition candidate") {
            AcquisitionFileViewService.attentionOf(completed(), overdueBefore) shouldBe emptyList()
            AcquisitionFileViewService.reacquisitionCandidate(completed()) shouldBe false
        }
        Then("each axis state, quarantined response and record status maps to exactly its reason") {
            mapOf(
                completed().copy(transmissionState = "TRANSMISSION_UNKNOWN", transmittedAt = null, resultState = "AWAITING", receiptState = "NONE",
                    recordStatusCounts = AcquisitionRecordStatusCounts(awaiting = 2)) to listOf(AcquisitionAttention.TRANSMISSION_UNKNOWN),
                completed().copy(transmissionState = "TRANSMISSION_QUARANTINED", transmittedAt = null, resultState = "AWAITING", receiptState = "NONE",
                    recordStatusCounts = AcquisitionRecordStatusCounts(awaiting = 2)) to listOf(AcquisitionAttention.TRANSMISSION_QUARANTINED),
                // ACQ-ADMIN-001 B2(§15 L-9): 재매입 파일 전송 보류 — 사유 추가(기존 행 불변).
                completed().copy(transmissionState = "TRANSMISSION_WITHHELD", transmittedAt = null, resultState = "AWAITING", receiptState = "NONE",
                    recordStatusCounts = AcquisitionRecordStatusCounts(awaiting = 1)) to listOf(AcquisitionAttention.TRANSMISSION_WITHHELD),
                completed().copy(receiptState = "RECEIPT_CONFLICT", resultState = "AWAITING", recordStatusCounts = AcquisitionRecordStatusCounts(awaiting = 2),
                    quarantinedResponses = 1) to listOf(AcquisitionAttention.RECEIPT_CONFLICT, AcquisitionAttention.RESPONSE_QUARANTINED, AcquisitionAttention.RESULT_OVERDUE),
                completed().copy(quarantinedResponses = 1) to listOf(AcquisitionAttention.RESPONSE_QUARANTINED),
                completed().copy(resultState = "DEADLINE_PASSED", recordStatusCounts = AcquisitionRecordStatusCounts(accepted = 1, unknown = 1)) to
                    listOf(AcquisitionAttention.RECORD_UNKNOWN, AcquisitionAttention.RESULT_DEADLINE_PASSED),
                completed().copy(recordStatusCounts = AcquisitionRecordStatusCounts(accepted = 1, conflict = 1)) to listOf(AcquisitionAttention.RECORD_CONFLICT),
                completed().copy(resultState = "AWAITING", recordStatusCounts = AcquisitionRecordStatusCounts(awaiting = 2)) to listOf(AcquisitionAttention.RESULT_OVERDUE),
            ).forEach { (summary, expected) -> AcquisitionFileViewService.attentionOf(summary, overdueBefore) shouldBe expected }
        }
        Then("RESULT_OVERDUE needs a transmitted AWAITING file at or before the deadline point, and is not computed without a configured deadline") {
            val awaiting = completed().copy(resultState = "AWAITING", recordStatusCounts = AcquisitionRecordStatusCounts(awaiting = 2))
            AcquisitionFileViewService.attentionOf(awaiting.copy(transmittedAt = overdueBefore), overdueBefore) shouldBe listOf(AcquisitionAttention.RESULT_OVERDUE)
            AcquisitionFileViewService.attentionOf(awaiting.copy(transmittedAt = overdueBefore.plusMillis(1)), overdueBefore) shouldBe emptyList()
            AcquisitionFileViewService.attentionOf(awaiting, null) shouldBe emptyList()
            AcquisitionFileViewService.attentionOf(awaiting.copy(transmissionState = "SEALED", transmittedAt = null), overdueBefore) shouldBe emptyList()
        }
        Then("unresolved latest-generation rejected records mark a re-acquisition candidate, which is not an attention reason (I-5, ACQ-ADMIN-001 §5.1)") {
            // 기대값 변경(ACQ-ADMIN-001 rev 0.7 — §5.1 L-9 배지 규칙·AC9, MINOR-6): 입력이 "거절 레코드 건수"에서 Reader가 센 재매입 단계 건수(어느 세대에도 확정·불요 종결이
            // 없고 더 높은 세대가 없는 거절 레코드)로 바뀌었다. 파일 전체 거절·REJECTED 레코드가 그 상태면 그대로 검토 대상이다(아래 두 줄 — rev 0.6과 같은 결과).
            val fileRejected = completed().copy(receiptState = "FILE_REJECTED", recordStatusCounts = AcquisitionRecordStatusCounts(fileRejected = 2), reacquisitionCandidates = 2)
            AcquisitionFileViewService.reacquisitionCandidate(fileRejected) shouldBe true
            AcquisitionFileViewService.attentionOf(fileRejected, overdueBefore) shouldBe emptyList()
            AcquisitionFileViewService.reacquisitionCandidate(
                completed().copy(recordStatusCounts = AcquisitionRecordStatusCounts(accepted = 1, rejected = 1), reacquisitionCandidates = 1),
            ) shouldBe true
        }
        Then("§5.1 three states: in progress (a newer generation, nothing left to review) and none once it is accepted — the original record still reads REJECTED") {
            val rejectedOnce = completed().copy(recordStatusCounts = AcquisitionRecordStatusCounts(rejected = 1))
            AcquisitionFileViewService.item(rejectedOnce.copy(reacquisitionInProgress = 1), overdueBefore).let { it.reacquisitionCandidate to it.reacquisitionInProgress } shouldBe
                (false to true)
            AcquisitionFileViewService.item(rejectedOnce.copy(reacquisitionCandidates = 1, reacquisitionInProgress = 1), overdueBefore)
                .let { it.reacquisitionCandidate to it.reacquisitionInProgress } shouldBe (true to false)
            // 새 세대 ACCEPTED: Reader가 단계를 세지 않는다(표지) → 배지 없음, 레코드 상태 건수(rejected = 1)는 이력 그대로.
            AcquisitionFileViewService.item(rejectedOnce, overdueBefore).let { it.reacquisitionCandidate to it.reacquisitionInProgress } shouldBe (false to false)
        }
    }

    Given("paging and the deadline point") {
        val criteria = AcquisitionFileSearchCriteria(businessDateFrom = day, businessDateTo = day, limit = 2)
        Then("the reader is asked for limit + 1 rows with now − deadline; the extra row becomes the next cursor and is not returned") {
            val reader = FakeReader(listOf(completed("ACQ.A.TEST.20261105.001"), completed("ACQ.B.TEST.20261105.001"), completed("ACQ.C.TEST.20261105.001")))
            val page = service(reader).search(criteria)
            reader.calls.single() shouldBe Triple("search", overdueBefore, 3)
            page.items.map { it.summary.fileId } shouldBe listOf("ACQ.A.TEST.20261105.001", "ACQ.B.TEST.20261105.001")
            page.nextCursor shouldBe AcquisitionFileCursor(day, "ACQ.B.TEST.20261105.001")
        }
        Then("a short page has no cursor; without a configured deadline the reader gets no deadline point") {
            val reader = FakeReader(listOf(completed()))
            service(reader, deadline = null).search(criteria).nextCursor.shouldBeNull()
            reader.calls.single() shouldBe Triple("search", null, 3)
        }
        Then("record pages work the same way (cursor = last seq) and a missing file is null") {
            val reader = FakeReader(recordRows = (1..4).map { record(it) })
            val page = service(reader).records("ACQ.SIMACQA.TEST.20261105.001", AcquisitionRecordSearchCriteria(limit = 3))!!
            page.items.map { it.seq } shouldBe listOf(1, 2, 3)
            page.nextCursor shouldBe 3
            service(FakeReader(recordRows = null)).records("ACQ.SIMACQA.TEST.20261105.001", AcquisitionRecordSearchCriteria()).shouldBeNull()
        }
        Then("the detail carries the first record page, the attention reasons and the read time; a missing file is null") {
            val rows = AcquisitionFileDetailRows(
                completed().copy(recordCount = 25, recordStatusCounts = AcquisitionRecordStatusCounts(accepted = 25)), "acq-cutoff-demo-1",
                Instant.parse("2026-11-05T14:00:00Z"), "a".repeat(64), 900, null, Instant.parse("2026-11-05T15:00:00Z"), AcquisitionHistory(1, emptyList()),
                AcquisitionHistory(2, emptyList()), (1..21).map { record(it) },
            )
            val reader = FakeReader(detailRows = rows)
            val d = service(reader).detail("ACQ.SIMACQA.TEST.20261105.001")!!
            reader.calls.single() shouldBe Triple("detail", AcquisitionRecordSearchCriteria(), 21)
            d.records.items.size shouldBe 20
            d.records.nextCursor shouldBe 20
            d.file.attention shouldBe emptyList()
            d.readAt shouldBe now
            service(FakeReader()).detail("ACQ.SIMACQA.TEST.20261105.001").shouldBeNull()
        }
    }

    Given("criteria invariants (the entry parser already checks formats)") {
        Then("period ends, the 31-day cap, exact lookup exclusivity and limits are enforced") {
            shouldThrow<IllegalArgumentException> { AcquisitionFileSearchCriteria() }
            shouldThrow<IllegalArgumentException> { AcquisitionFileSearchCriteria(businessDateFrom = day) }
            shouldThrow<IllegalArgumentException> { AcquisitionFileSearchCriteria(businessDateFrom = day, businessDateTo = day.plusDays(31)) }
            AcquisitionFileSearchCriteria(businessDateFrom = day, businessDateTo = day.plusDays(30))
            shouldThrow<IllegalArgumentException> { AcquisitionFileSearchCriteria(businessDateFrom = day, businessDateTo = day.minusDays(1)) }
            shouldThrow<IllegalArgumentException> { AcquisitionFileSearchCriteria(fileId = "ACQ.SIMACQA.TEST.20261105.001", needsAttention = true) }
            shouldThrow<IllegalArgumentException> { AcquisitionFileSearchCriteria(businessDateFrom = day, businessDateTo = day, limit = 51) }
            shouldThrow<IllegalArgumentException> { AcquisitionRecordSearchCriteria(status = "PENDING") }
            shouldThrow<IllegalArgumentException> { AcquisitionRecordSearchCriteria(afterSeq = -1) }
        }
    }
})
