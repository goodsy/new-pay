package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.ProviderRequestRef

/**
 * 원 기관 요청 ID 조회 한 번(AUTH-001 §5.4 — 새 기관 요청 ID를 만들지 않는다). [inquiryId]는 조회마다 새 값(`aq_` + ULID, AU-30). [closeIfAbsent]는 무효과 종결 요청이며
 * Route Capability `confirmsNoEffect`가 있을 때만 전송한다(없으면 Adapter가 false로 보낸다 — 프로파일 B).
 */
data class AuthorizationInquiryCall(
    val attemptId: AttemptId,
    val providerRequest: ProviderRequestRef,
    val routeRef: String,
    val inquiryId: String,
    val closeIfAbsent: Boolean,
)

/** 조회가 결과를 주지 못한 이유(관찰 없음). */
enum class InquiryUnavailableReason { INSTITUTION_UNAVAILABLE, CONNECTION, TIMEOUT, FRAMING, INTEGRITY, CORRELATION, UNKNOWN_ROUTE }

/** 조회 결과(AUTH-001 §5.7.2). NOT_FOUND는 관찰로만 남고 종료 근거가 아니다(시도 §2). */
sealed interface InquiryCallResult {
    /** 원 결정(확정) 또는 PENDING 예약(rev 0.3 H-3 — 종료 근거 아님). */
    data class Found(val observation: InstitutionObservation) : InquiryCallResult

    data class NotFound(val observation: InstitutionObservation) : InquiryCallResult

    /** Capability 있는 기관의 무효과 종결 확정. */
    data class NoEffectConfirmed(val observation: InstitutionObservation) : InquiryCallResult

    data class Unavailable(val reason: InquiryUnavailableReason) : InquiryCallResult
}

/** 기관 조회 Port(AUTH-001 §5.7). **DB TX 밖에서만** 부른다. 구현은 Simulator adapter뿐이다(REAL 없음 — ArchUnit). */
interface AuthorizationInquiryPort {
    fun inquire(call: AuthorizationInquiryCall): InquiryCallResult
}
