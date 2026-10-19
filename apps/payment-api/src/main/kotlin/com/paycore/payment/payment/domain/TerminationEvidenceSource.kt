package com.paycore.payment.payment.domain

import java.time.Instant

/** 종료 근거의 출처 종류(AUTH-001 rev 0.3 m-6, AUTH-CONTRACT-001 §5.5 출처 표). */
enum class TerminationEvidenceSourceKind {
    /** 기관 결과 관찰(응답·조회·통보·대사) — 확정 거절·무효과 확정의 유일한 출처. [TerminationEvidenceSource.ref] = 관찰 키(`attemptId/출처/기관 결과 ID`). */
    OBSERVATION,

    /** 전송 전 포기한 Attempt(게이트 거절·회복 fencing·connect 실패) — 전송 전 실패 확정의 유일한 출처. ref = attemptId. */
    ATTEMPT_ABANDON,

    /** REQUESTED 결제창 만료 — 미전송·세션 폐기의 출처. ref = paymentId. */
    CHECKOUT_EXPIRY,
}

/**
 * 종료 근거 출처 기록(Auditability — 무엇이 근거였고 언제 누가 기록했나). [recordedBy]는 기록 주체 식별자다: 자동 경로는 [SYSTEM], 운영자 경로(대사 보정 등, 후속)는 운영자 ID.
 * 요청·회차 인과 값은 Outbox·로그가 갖는다. 원문 비밀·카드정보는 넣지 않는다.
 */
data class TerminationEvidenceSource(
    val kind: TerminationEvidenceSourceKind,
    val ref: String,
    val recordedAt: Instant,
    val recordedBy: String,
) {
    init {
        require(ref.isNotEmpty() && ref.length <= MAX_REF) { "evidence source reference must be 1..$MAX_REF characters" }
        require(recordedBy.isNotEmpty() && recordedBy.length <= MAX_RECORDER) { "evidence recorder must be 1..$MAX_RECORDER characters" }
    }

    companion object {
        /** PAYMENT 자동 처리 경로(승인 결과 반영·전송 전 포기·결제창 만료)의 기록 주체. */
        const val SYSTEM = "system:payment-api"

        const val MAX_REF = 200
        const val MAX_RECORDER = 64

        fun observation(key: ObservationKey, at: Instant): TerminationEvidenceSource =
            TerminationEvidenceSource(TerminationEvidenceSourceKind.OBSERVATION, "${key.attemptId.value}/${key.source}/${key.sourceResultId}", at, SYSTEM)

        fun attemptAbandon(attemptId: AttemptId, at: Instant): TerminationEvidenceSource =
            TerminationEvidenceSource(TerminationEvidenceSourceKind.ATTEMPT_ABANDON, attemptId.value, at, SYSTEM)

        fun checkoutExpiry(paymentId: PaymentId, at: Instant): TerminationEvidenceSource =
            TerminationEvidenceSource(TerminationEvidenceSourceKind.CHECKOUT_EXPIRY, paymentId.value, at, SYSTEM)
    }
}

/** 기록된 종료 근거 하나. [source]가 null이면 AUTH-001 이전에 저장된 legacy 행이다(영속 복원에서만 생긴다). */
data class TerminationEvidenceRecord(val evidence: TerminationEvidence, val source: TerminationEvidenceSource?)
