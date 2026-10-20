package com.paycore.payment.payment.domain

import java.time.Instant
import java.time.LocalDate

/**
 * 기관이 확정한 취소 조건(취소 성공 관찰에만, CANCEL-CONTRACT-001 §4.1). [originalProviderTransactionId]는 취소한 원 승인 거래의 기관 거래 식별자,
 * [providerTransactionId]는 취소 자체의 기관 거래 식별자다. 취소 승인번호는 기관이 주지 않을 수 있다(null, 빈 문자열은 거부). [institutionTransactionDate]는
 * 기관 취소 거래일 — 정산 거래일(취소 완료일)의 근거이며 PG 수신 시각과 구분한다(K-8·K-9, 시간대 규약은 Q-CALENDAR). [institutionBusinessDate]는 기관이 주면 보존만
 * 한다(CANCEL-001 F-12 — 승인 `ApprovedTerms`와 같은 모양, 의미·정산 적용은 Q-CALENDAR).
 */
data class CancelConfirmedTerms(
    val money: Money,
    val originalProviderTransactionId: String,
    val providerTransactionId: String,
    val cancelApprovalNumber: String?,
    val institutionTransactionDate: LocalDate,
    val institutionBusinessDate: LocalDate? = null,
) {
    init {
        require(originalProviderTransactionId.isNotEmpty() && providerTransactionId.isNotEmpty()) {
            "original and cancel provider transaction ids must not be empty"
        }
        require(cancelApprovalNumber == null || cancelApprovalNumber.isNotEmpty()) { "cancel approval number must not be empty when present" }
    }
}

/** 취소 관찰 유일 키(승인 CA-15와 같은 방식): 같은 출처의 같은 기관 결과는 한 번만 기록한다. */
data class CancelObservationKey(val attemptId: CancelAttemptId, val source: ObservationSource, val sourceResultId: String)

/**
 * 기관 취소 결과 한 건(append-only). 판정과 무관하게 기록한다. 원문 증거는 [evidenceHash] 참조만. 거절·무효과·UNKNOWN·중복 수신은 거래가 아니라
 * 이 관찰로만 남는다(D-AUTH-003). [InstitutionOutcome.NOT_FOUND]는 무효과가 아니다(K-14).
 */
data class CancelObservation(
    val attemptId: CancelAttemptId,
    val source: ObservationSource,
    val sourceResultId: String,
    val outcome: InstitutionOutcome,
    val confirmed: CancelConfirmedTerms?,
    val evidenceHash: String?,
    val observedAt: Instant,
) {
    init {
        require(sourceResultId.isNotEmpty()) { "sourceResultId must not be empty" }
        require((outcome == InstitutionOutcome.APPROVED) == (confirmed != null)) { "confirmed terms exist exactly for APPROVED observations" }
    }

    val key: CancelObservationKey
        get() = CancelObservationKey(attemptId, source, sourceResultId)

    /** 기관이 정의한 내용이 같은가(승인 관찰 review round2 M-2와 같은 정의): 수신 시각·원문 hash는 비교하지 않는다. */
    fun sameInstitutionContent(other: CancelObservation): Boolean =
        attemptId == other.attemptId && source == other.source && sourceResultId == other.sourceResultId &&
            outcome == other.outcome && confirmed == other.confirmed
}
