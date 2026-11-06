package com.paycore.financial.acquiring.domain

import java.time.Instant

/**
 * 레코드 결과 축(append-only — D-ACQ-004·D-ACQ-010, ACQUIRING-001 §4.1). 레코드(파일, 순번)마다 결과 행이 쌓이고 현재 상태는 행에서 계산한다(행을 고치지 않는다).
 *
 * 사용자 결정:
 * - D-ACQ-004: ACCEPTED = 매입 확정, REJECTED = 그 파일에서 종결, 마감까지 결과 없음 = UNKNOWN → 대사(자동 재전송 없음).
 * - D-ACQ-010: 늦게 온 유효 결과는 자동 반영(UNKNOWN 행 보존), ACCEPTED 인정 시각 = 수신 시각, 기존 결과와 상충하면 격리(CONFLICT 행).
 * - D-ACQ-004·I-7: 파일 전체 거절(ACK FILE_REJECTED)이면 그 파일 레코드가 모두 종결된다(REJECTED, 출처 FILE_REJECTED — 재매입은 D-ACQ-011 2인 승인).
 *
 * Implementer 해석(사용자 결정 아님 — I-46): 같은 결과의 반복은 중복(행 없음). "같음" = 결과·사유 코드·출처 종류가 모두 같다(REJECTED 사유가 다르면 상충 — fail-closed).
 * 이미 CONFLICT인 레코드에는 더 쓰지 않는다(격리 유지 — 응답 파일 기록이 근거를 남긴다).
 */
object RecordResultRule {

    fun status(entries: List<RecordResultEntry>): RecordStatus {
        if (entries.any { it.result == RecordResult.CONFLICT }) return RecordStatus.CONFLICT
        val definitive = entries.firstOrNull { it.result == RecordResult.ACCEPTED || it.result == RecordResult.REJECTED }
        return when {
            definitive == null -> if (entries.any { it.result == RecordResult.UNKNOWN }) RecordStatus.UNKNOWN else RecordStatus.AWAITING
            definitive.result == RecordResult.ACCEPTED -> RecordStatus.ACCEPTED
            definitive.source == RecordResultSource.FILE_REJECTED -> RecordStatus.FILE_REJECTED
            else -> RecordStatus.REJECTED
        }
    }

    /**
     * 기관 결과 하나를 기존 행에 반영한다. 결과 = 새 행(결정·상충) 또는 중복(행 없음).
     *
     * 사용자 결정 D-ACQ-014(rev 1.1): 거래·종류당 **세대를 넘는 ACCEPTED는 하나**다. [otherGenerationAccepted] = 같은 거래·종류의 다른 레코드(다른 세대)가 이미
     * ACCEPTED다 — 그러면 이 레코드의 ACCEPTED는 결정이 아니라 CONFLICT 행(격리)이다(예: 재매입 세대가 ACCEPTED된 뒤 앞 세대의 늦은 ACCEPTED). REJECTED는 영향 없다.
     */
    fun apply(
        entries: List<RecordResultEntry>,
        incoming: IncomingRecordResult,
        recognizedAt: Instant,
        otherGenerationAccepted: Boolean = false,
    ): RecordResultApplication {
        val nextNo = (entries.maxOfOrNull { it.entryNo } ?: 0) + 1
        return when (status(entries)) {
            RecordStatus.CONFLICT -> RecordResultApplication.AlreadyConflicted
            RecordStatus.AWAITING, RecordStatus.UNKNOWN ->
                if (otherGenerationAccepted && incoming.result == LineResult.ACCEPTED) {
                    RecordResultApplication.Conflict(RecordResultEntry(nextNo, RecordResult.CONFLICT, RecordResult.ACCEPTED, null, incoming.source, recognizedAt))
                } else {
                    RecordResultApplication.Append(RecordResultEntry(nextNo, incoming.result.recordResult, null, incoming.reasonCode, incoming.source, recognizedAt))
                }
            RecordStatus.ACCEPTED, RecordStatus.REJECTED, RecordStatus.FILE_REJECTED -> {
                val existing = entries.first { it.result == RecordResult.ACCEPTED || it.result == RecordResult.REJECTED }
                if (existing.result == incoming.result.recordResult && existing.reasonCode == incoming.reasonCode && existing.source == incoming.source) {
                    RecordResultApplication.Duplicate
                } else {
                    RecordResultApplication.Conflict(
                        RecordResultEntry(nextNo, RecordResult.CONFLICT, incoming.result.recordResult, incoming.reasonCode, incoming.source, recognizedAt),
                    )
                }
            }
        }
    }

    /** 결과 마감: 결과가 하나도 없는 레코드만 UNKNOWN 행을 받는다(D-ACQ-004). 그 밖은 null. */
    fun closeAtDeadline(entries: List<RecordResultEntry>, at: Instant): RecordResultEntry? =
        if (status(entries) == RecordStatus.AWAITING) RecordResultEntry((entries.maxOfOrNull { it.entryNo } ?: 0) + 1, RecordResult.UNKNOWN, null, null, RecordResultSource.DEADLINE, at)
        else null

    /** 파일 결과 축 요약: 모든 레코드가 결정(ACCEPTED·REJECTED — 상충 포함)을 가졌는가. */
    fun allDecided(statuses: Collection<RecordStatus>): Boolean = statuses.all { it != RecordStatus.AWAITING && it != RecordStatus.UNKNOWN }

    /** CANCEL 판정(D-ACQ-009) 입력으로의 사상. 파일 거절 = 매입되지 않음(REJECTED). */
    fun toOriginalSaleResult(status: RecordStatus): OriginalSaleResult = when (status) {
        RecordStatus.AWAITING -> OriginalSaleResult.AWAITING
        RecordStatus.ACCEPTED -> OriginalSaleResult.ACCEPTED
        RecordStatus.REJECTED, RecordStatus.FILE_REJECTED -> OriginalSaleResult.REJECTED
        RecordStatus.UNKNOWN -> OriginalSaleResult.UNKNOWN
        RecordStatus.CONFLICT -> OriginalSaleResult.CONFLICT
    }
}

/**
 * 결과 행(append-only). [result] = ACCEPTED·REJECTED·UNKNOWN·CONFLICT, [observedResult] = CONFLICT 행의 들어온 결과(그 밖 null), [reasonCode] = REJECTED(또는 상충한
 * REJECTED)의 기관 사유, [recognizedAt] = FINANCIAL이 그 결과를 받은 시각(ACCEPTED 인정 시각 — D-ACQ-010) 또는 마감 시각(UNKNOWN).
 */
data class RecordResultEntry(
    val entryNo: Int,
    val result: RecordResult,
    val observedResult: RecordResult?,
    val reasonCode: String?,
    val source: RecordResultSource,
    val recognizedAt: Instant,
) {
    init {
        if (entryNo < 1) throw AcquisitionContractViolation("result entry numbers start at 1")
        reasonCode?.let { AcquisitionText.token("reasonCode", it) }
        val shapeOk = when (result) {
            RecordResult.ACCEPTED -> observedResult == null && reasonCode == null && source == RecordResultSource.RESULT_FILE
            RecordResult.REJECTED -> observedResult == null && reasonCode != null && source != RecordResultSource.DEADLINE
            RecordResult.UNKNOWN -> observedResult == null && reasonCode == null && source == RecordResultSource.DEADLINE
            RecordResult.CONFLICT -> (observedResult == RecordResult.ACCEPTED || observedResult == RecordResult.REJECTED) &&
                ((observedResult == RecordResult.REJECTED) == (reasonCode != null)) && source != RecordResultSource.DEADLINE
        }
        if (!shapeOk) throw AcquisitionContractViolation("record result entry shape")
    }
}

/** 기관에서 들어온 결과(결과 파일 줄 또는 ACK FILE_REJECTED). */
data class IncomingRecordResult(val result: LineResult, val reasonCode: String?, val source: RecordResultSource) {
    init {
        if ((result == LineResult.REJECTED) != (reasonCode != null)) throw AcquisitionContractViolation("only a rejected result carries a reason code")
        if (source == RecordResultSource.DEADLINE) throw AcquisitionContractViolation("an institution result does not come from the deadline")
        if (source == RecordResultSource.FILE_REJECTED && result != LineResult.REJECTED) throw AcquisitionContractViolation("a file rejection rejects its records")
    }
}

private val LineResult.recordResult: RecordResult
    get() = when (this) {
        LineResult.ACCEPTED -> RecordResult.ACCEPTED
        LineResult.REJECTED -> RecordResult.REJECTED
    }

sealed interface RecordResultApplication {
    data class Append(val entry: RecordResultEntry) : RecordResultApplication

    data class Conflict(val entry: RecordResultEntry) : RecordResultApplication

    data object Duplicate : RecordResultApplication

    data object AlreadyConflicted : RecordResultApplication
}

/** 결과 행 종류(DB CHECK와 같은 집합). */
enum class RecordResult { ACCEPTED, REJECTED, UNKNOWN, CONFLICT }

/** 결과 출처: 결과 파일 줄, ACK 파일 전체 거절, 결과 마감(UNKNOWN). */
enum class RecordResultSource { RESULT_FILE, FILE_REJECTED, DEADLINE }

/** 레코드 현재 상태(행에서 계산). FILE_REJECTED = 파일 전체 거절로 종결. */
enum class RecordStatus { AWAITING, ACCEPTED, REJECTED, FILE_REJECTED, UNKNOWN, CONFLICT }
