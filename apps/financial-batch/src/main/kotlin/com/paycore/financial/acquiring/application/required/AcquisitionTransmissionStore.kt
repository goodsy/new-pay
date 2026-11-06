package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.TransmissionOutcome
import com.paycore.financial.acquiring.domain.TransmissionState
import java.time.Instant

/**
 * 전송 시도·결과 기록 Port(CONVENTIONS §14 — Aggregate가 아닌 프로토콜 기록 → Store). append-only: 점유 TX가 시도 행을, 결과 TX가 결과 행을 INSERT한다.
 * 호출자 TX 필수. 같은 (파일, 시도 번호) 두 번째 INSERT는 UNIQUE 위반(PK).
 */
interface AcquisitionTransmissionStore {
    /** 다음 시도 번호(기존 최대 + 1). 파일 행 version 비교와 PK가 동시 점유를 막는다. */
    fun nextAttemptNo(fileId: AcquisitionFileId): Int

    fun appendAttempt(attempt: TransmissionAttemptRecord)

    fun appendOutcome(outcome: TransmissionOutcomeRecord)

    /** 시험·대사용 조회: 파일의 시도(번호 순)와 결과. */
    fun history(fileId: AcquisitionFileId): List<TransmissionHistoryEntry>
}

data class TransmissionAttemptRecord(
    val fileId: AcquisitionFileId,
    val attemptNo: Int,
    val resumeState: TransmissionState,
    val contentSha256: String,
    val claimedAt: Instant,
    val leaseUntil: Instant,
)

/**
 * [superseded] = 이 시도의 lease가 지나 다른 시도가 이어받은 뒤의 관찰 기록(리뷰 m-5 — outcome SUPERSEDED, observed = [outcome] 코드, 상태 불변:
 * [resultingState] = 그때 파일의 현재 상태).
 */
data class TransmissionOutcomeRecord(
    val fileId: AcquisitionFileId,
    val attemptNo: Int,
    val outcome: TransmissionOutcome,
    val resultingState: TransmissionState,
    val decidedAt: Instant,
    val superseded: Boolean = false,
)

/** [outcome]/[observed]/[evidence]/[reason] = 저장 코드(결과 행이 없으면 null — 진행 중이거나 중단된 시도). */
data class TransmissionHistoryEntry(
    val attemptNo: Int,
    val resumeState: TransmissionState,
    val outcome: String?,
    val observed: String?,
    val evidence: String?,
    val reason: String?,
    val resultingState: TransmissionState?,
)
