package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancelObservation
import com.paycore.payment.payment.domain.ProviderRequestRef

/**
 * 원 취소 요청 ID 조회 한 번(CANCEL-001 §6.5 — 새 기관 요청 ID를 만들지 않는다). [inquiryId]는 조회마다 새 값(`cq_` + ULID, CN-07). [closeIfAbsent]는 무효과 종결 요청이며
 * Route 취소 Capability `confirmsNoEffect`가 있을 때만 전송한다(없으면 Adapter가 false로 보낸다).
 */
data class CancelInquiryCall(
    val attemptId: CancelAttemptId,
    val providerRequest: ProviderRequestRef,
    val routeRef: String,
    val inquiryId: String,
    val closeIfAbsent: Boolean,
)

/** 취소 조회 결과(CANCEL-001 §6.6 표). NOT_FOUND는 관찰로만 남고 종료 근거가 아니다. */
sealed interface CancelInquiryCallResult {
    /** 원 결정(확정 — 거절이면 [declineCategory]) 또는 PENDING(종료 근거 아님). */
    data class Found(val observation: CancelObservation, val declineCategory: String? = null) : CancelInquiryCallResult

    data class NotFound(val observation: CancelObservation) : CancelInquiryCallResult

    /** 취소 무효과 확정 Capability 있는 기관의 무효과 종결 확정. */
    data class NoEffectConfirmed(val observation: CancelObservation) : CancelInquiryCallResult

    data class Unavailable(val reason: InquiryUnavailableReason) : CancelInquiryCallResult
}

/** 기관 취소 조회 Port(CANCEL-001 §6.6). **DB TX 밖에서만** 부른다. 구현은 Simulator adapter뿐이다(REAL 없음 — ArchUnit). */
interface CancelInquiryPort {
    fun inquire(call: CancelInquiryCall): CancelInquiryCallResult
}
