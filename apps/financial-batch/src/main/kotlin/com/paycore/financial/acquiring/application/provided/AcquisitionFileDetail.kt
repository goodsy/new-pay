package com.paycore.financial.acquiring.application.provided

import java.time.Instant
import java.time.LocalDate

/**
 * 매입 파일 상세(ACQ-VIEW-001 §4.2 — 한 읽기 전용 REPEATABLE READ TX). [file]은 목록 항목과 같은 모양이고, 그 위에 봉인 근거(마감 정책 버전·마감 시각·봉인 hash·크기),
 * 전송 시도·응답 처리 기록 요약(최근 [AcquisitionHistory.LATEST]건 + 전체 건수 — I-7), 레코드 첫 쪽, 조회 시각([readAt] — I-6)을 담는다.
 * 숨기는 값(§4.5): 봉인·응답 바이트, 응답 파일명, 송신자 ID, D-ACQV-002 기관 값 — 타입에 칸이 없다.
 */
data class AcquisitionFileDetail(
    val file: AcquisitionFileListItem,
    val cutoffPolicyVersion: String,
    val cutoffAt: Instant,
    val contentSha256: String,
    val contentBytes: Int,
    val transmissionLeaseUntil: Instant?,
    val updatedAt: Instant,
    val attempts: AcquisitionHistory<AcquisitionAttemptView>,
    val responses: AcquisitionHistory<AcquisitionResponseView>,
    val records: AcquisitionRecordPage,
    val readAt: Instant,
)

/**
 * 목록 항목(§4.1) = 저장 요약([summary]) + 서버가 계산한 "확인 필요" 사유([attention] — §4.4, I-4) + 재매입 검토 대상 표시([reacquisitionCandidate] — I-5: 수신 축
 * FILE_REJECTED 또는 REJECTED·FILE_REJECTED 레코드가 있음. 실행은 ACQ-ADMIN-001).
 */
data class AcquisitionFileListItem(
    val summary: AcquisitionFileSummary,
    val attention: List<AcquisitionAttention>,
    val reacquisitionCandidate: Boolean,
    /** (ACQ-ADMIN-001 B2 — §5.1 3상태) 검토 대상은 아니고 새 세대가 진행 중(전송·결과 대기 또는 그 세대 파일에서 다시 검토). 새 세대 ACCEPTED면 둘 다 false. */
    val reacquisitionInProgress: Boolean = false,
)

/** 파일 행 요약(저장 값 그대로 — 3축은 서로 추정하지 않는다, D-ACQ-004) + 레코드 현재 상태별 건수 + 격리된 응답 수. 통화는 KRW만(파일 CHECK — I-5). */
data class AcquisitionFileSummary(
    val fileId: String,
    val acquirerCode: String,
    val environment: String,
    val businessDate: LocalDate,
    val fileVersion: Int,
    val originalFileId: String?,
    val recordCount: Int,
    val saleCount: Int,
    val saleAmountMinor: Long,
    val cancelCount: Int,
    val cancelAmountMinor: Long,
    val netAmountMinor: Long,
    val transmissionState: String,
    val receiptState: String,
    val resultState: String,
    val sealedAt: Instant,
    val transmittedAt: Instant?,
    val recordStatusCounts: AcquisitionRecordStatusCounts,
    val quarantinedResponses: Int,
    /** (ACQ-ADMIN-001 B2 — §5.1 L-9) 재매입 단계 CANDIDATE·IN_PROGRESS 레코드 수(ReacquisitionBadgeRule.recordStage — Reader가 같은 정의로 센다). */
    val reacquisitionCandidates: Int = 0,
    val reacquisitionInProgress: Int = 0,
) {
    val currency: String get() = CURRENCY

    companion object {
        const val CURRENCY = "KRW"
    }
}

/** 레코드 현재 상태별 건수(`RecordResultRule.status`와 같은 정의). */
data class AcquisitionRecordStatusCounts(
    val awaiting: Int = 0,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val fileRejected: Int = 0,
    val unknown: Int = 0,
    val conflict: Int = 0,
)

/** "확인 필요" 사유(§4.4 — 표시 분류일 뿐 상태를 바꾸지 않는다). */
enum class AcquisitionAttention {
    TRANSMISSION_UNKNOWN,
    TRANSMISSION_QUARANTINED,

    /** (ACQ-ADMIN-001 B2 — §15 L-9) 재매입 파일 전송 보류(다른 세대 매입 확정 — D-ACQ-014, 대사). */
    TRANSMISSION_WITHHELD,
    RECEIPT_CONFLICT,
    RESPONSE_QUARANTINED,
    RECORD_UNKNOWN,
    RECORD_CONFLICT,
    RESULT_DEADLINE_PASSED,
    RESULT_OVERDUE,
}

/** 기록 요약: 전체 건수 + 최근 [LATEST]건(최신순). */
data class AcquisitionHistory<T>(val total: Int, val latest: List<T>) {
    companion object {
        const val LATEST = 20
    }
}

/** 전송 시도(점유)와 그 결과(없으면 진행 중 — 결과 열 null). SFTP 접속 정보·경로는 DB에 없다. */
data class AcquisitionAttemptView(
    val attemptNo: Int,
    val resumeState: String,
    val claimedAt: Instant,
    val leaseUntil: Instant,
    val outcome: String?,
    val observed: String?,
    val evidence: String?,
    val reason: String?,
    val resultingState: String?,
    val decidedAt: Instant?,
)

/** 기관 응답 처리 기록(ACK·결과). 응답 파일명·바이트는 내보내지 않는다(종류 + 순번으로 표시 — §4.5). */
data class AcquisitionResponseView(
    val kind: String,
    val sequenceNo: Int,
    val disposition: String,
    val quarantineReason: String?,
    val ackOutcome: String?,
    val ackReasonCode: String?,
    val evidence: String?,
    val institutionAt: Instant?,
    val observedAt: Instant,
)

/**
 * 레코드(§4.3 — D-ACQV-002: 승인번호·기관 거래번호·원 기관 거래번호·발급사·카드 종류·할부는 SELECT하지 않는다). [merchantAccountId] = 파일 v1 merchantRef(D-ACQ-008 MID).
 * [status] = 결과 행에서 계산한 현재 상태(6값).
 */
data class AcquisitionRecordView(
    val seq: Int,
    val recordType: String,
    val transactionId: String,
    val originalTransactionId: String?,
    val merchantAccountId: String,
    val institutionTransactionDate: LocalDate,
    val eventAt: Instant,
    val currency: String,
    val approvedAmountMinor: Long,
    val nettedCancelAmountMinor: Long,
    val recordAmountMinor: Long,
    val generation: Int,
    val reacquisitionOf: String?,
    val status: String,
    val results: List<AcquisitionRecordResultView>,
)

/** 레코드 결과 행(append-only — D-ACQ-010). 응답은 [responseRef](종류 + 순번)로만 가리킨다(DEADLINE 출처는 응답 없음 → null). 명세 §4.3 모양(리뷰 MINOR-2). */
data class AcquisitionRecordResultView(
    val entryNo: Int,
    val result: String,
    val observedResult: String?,
    val reasonCode: String?,
    val source: String,
    val responseRef: AcquisitionResponseRef?,
    val recognizedAt: Instant,
)

/** 결과 행이 근거로 삼은 기관 응답(응답 PK·파일명 대신 종류 + 순번 — I-12). */
data class AcquisitionResponseRef(val kind: String, val sequenceNo: Int)
