package com.paycore.simulator.acquirer.domain

import java.time.Duration
import java.time.Instant

/** 판정된 레코드(게시 계획 입력). */
data class JudgedRecord(val record: ReceivedRecord, val judgement: RecordJudgement, val scenario: AcquirerScenarioRule?)

/**
 * 게시 계획 규칙(ACQUIRING-001 §7, Implementer 해석 I-30). 시각은 수신 시각 + 설정 지연(Demo)으로만 정한다(난수 없음).
 * - ACK: 수신 시각 + ack 지연(+ DELAY_ACK). DROP_ACK이면 없음. 수신 판정 셋(RECEIVED·FILE_REJECTED·RECEIPT_CONFLICT) 모두 ACK를 낸다.
 * - 결과(RECEIVED만): 기본 회차 = 수신 시각 + 결과 지연. DELAY_RESULT는 + 시나리오 지연의 별도 회차, WITHHOLD_RESULT는 어느 회차에도 없음,
 *   DUPLICATE_RESULT·CONFLICTING_RESULT는 같은 시각의 **다음** 회차에 같은 결과·반대 결과를 한 번 더 싣는다. 회차 번호는 (시각, 원본→반복) 순서로 01부터.
 * - 레코드 0건 파일은 R 줄 없는 결과 파일 하나(완료 근거). 레코드가 있는데 모두 누락이면 결과 파일이 없다.
 */
object PublicationPlanner {

    fun plan(
        receipt: AcquirerReceipt,
        judged: List<JudgedRecord>,
        scenarios: AcquirerScenarioBook,
        ackDelay: Duration,
        resultDelay: Duration,
    ): PublicationPlan {
        require(!ackDelay.isNegative && !resultDelay.isNegative) { "delays must not be negative" }
        require(receipt.outcome == ReceiptOutcome.RECEIVED || judged.isEmpty()) { "only a RECEIVED file has record judgements" }
        receipt.recordCount?.let { require(it == judged.size) { "every record of a received file is judged" } }
        val ref = receipt.ref
        val ackDueAt = if (scenarios.ackDropped(ref)) null else receipt.receivedAt.plus(ackDelay).plus(scenarios.ackDelay(ref))
        if (receipt.outcome != ReceiptOutcome.RECEIVED) return PublicationPlan(ackDueAt, emptyList())

        val base = receipt.receivedAt.plus(resultDelay)
        if (judged.isEmpty()) return PublicationPlan(ackDueAt, listOf(PlannedResultFile(1, base, emptyList())))
        val slots = sortedMapOf<Slot, MutableList<PlannedResultLine>>()
        fun put(slot: Slot, line: PlannedResultLine) = slots.getOrPut(slot) { mutableListOf() }.add(line)
        judged.forEach { j ->
            val line = PlannedResultLine(j.record.seq, j.record.transactionId, j.record.type, j.judgement.result, j.judgement.reason, j.record.recordAmountMinor)
            when (j.scenario?.action) {
                AcquirerScenarioAction.WITHHOLD_RESULT -> Unit
                AcquirerScenarioAction.DELAY_RESULT -> put(Slot(base.plus(checkNotNull(j.scenario.delay)), echo = false), line)
                AcquirerScenarioAction.DUPLICATE_RESULT -> {
                    put(Slot(base, echo = false), line)
                    put(Slot(base, echo = true), line)
                }
                AcquirerScenarioAction.CONFLICTING_RESULT -> {
                    put(Slot(base, echo = false), line)
                    put(Slot(base, echo = true), opposite(line))
                }
                else -> put(Slot(base, echo = false), line)
            }
        }
        require(slots.size <= AcquisitionFileRef.MAX_SEQ) { "a received file can have at most ${AcquisitionFileRef.MAX_SEQ} result files" }
        val files = slots.entries.mapIndexed { i, (slot, lines) -> PlannedResultFile(i + 1, slot.dueAt, lines.sortedBy { it.recordSeq }) }
        return PublicationPlan(ackDueAt, files)
    }

    private fun opposite(line: PlannedResultLine): PlannedResultLine = when (line.result) {
        RecordResult.ACCEPTED -> line.copy(result = RecordResult.REJECTED, reason = SCENARIO_CONFLICT)
        RecordResult.REJECTED -> line.copy(result = RecordResult.ACCEPTED, reason = null)
    }

    private data class Slot(val dueAt: Instant, val echo: Boolean) : Comparable<Slot> {
        override fun compareTo(other: Slot): Int = compareValuesBy(this, other, { it.dueAt }, { it.echo })
    }

    /** 결과 상충 시나리오가 게시하는 반대 결과(ACCEPTED → REJECTED)의 사유. */
    const val SCENARIO_CONFLICT = "SCENARIO_CONFLICT"
}
