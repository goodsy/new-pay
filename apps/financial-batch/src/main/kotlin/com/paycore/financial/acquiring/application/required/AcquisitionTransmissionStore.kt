package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionRecordType
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

    /**
     * (ACQ-ADMIN-001 B2 — §15 L-9, D-ACQ-014) 파일 [fileId]의 레코드 중 같은 거래·종류의 매입 확정 표지가 **다른** 레코드(다른 세대)를 가리키는 것(순번 순).
     * 호출자는 그 다른 세대 파일 행을 먼저 잠근다([AcquisitionFileRepository.lockOtherGenerationFiles]) — 표지를 쓰는 응답 TX와 직렬화.
     */
    fun acceptedInOtherGeneration(fileId: AcquisitionFileId): List<AcceptedElsewhere>

    /** 전송 보류 근거(append-only — 레코드마다 한 행, 같은 (파일, 순번) 두 번째는 UNIQUE 위반). */
    fun appendWithholds(withholds: List<TransmissionWithholdRecord>)
}

/** 보류할 파일의 레코드 [seq]([transactionId]·[recordType])와 그 표지가 가리키는 다른 세대 레코드([acceptedFileId]#[acceptedSeq], 세대 [acceptedGeneration]). */
data class AcceptedElsewhere(
    val seq: Int,
    val transactionId: String,
    val recordType: AcquisitionRecordType,
    val acceptedFileId: AcquisitionFileId,
    val acceptedSeq: Int,
    val acceptedGeneration: Int,
)

/** 전송 보류 근거 한 행. [priorState] = 보류 직전 전송 축(SEALED·TRANSMITTING·TRANSMISSION_UNKNOWN). */
data class TransmissionWithholdRecord(val fileId: AcquisitionFileId, val evidence: AcceptedElsewhere, val priorState: TransmissionState, val withheldAt: Instant)

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
