package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.PaymentId
import java.time.Instant

/** 응답 상태(AC1 §10.1): 확정이면 200(AUTHORIZED·FAILED), 진행 중이면 202(IN_PROGRESS = 아직 결과 없음·전송 전, UNKNOWN = 결과 불명 — 회복 중). */
enum class AuthorizationViewStatus { AUTHORIZED, FAILED, IN_PROGRESS, UNKNOWN }

/** FAILED의 고정 분류: 기관 확정 거절 / 미전송 확정 / 기관 무효과 확정. */
enum class FailureCategory { DECLINED, NOT_SENT, NO_EFFECT }

/**
 * confirm 응답의 승인 상태(AUTH-001 §4.1). AUTHORIZED면 [approved]·[approvedAt], FAILED면 [failureCategory]. 카드번호·인증 증거·기관 원문·기관 계약계정·내부 버전은 없다.
 */
data class AuthorizationView(
    val paymentId: PaymentId,
    val authorizationId: AuthorizationId,
    val status: AuthorizationViewStatus,
    val approved: ApprovedTerms?,
    val approvedAt: Instant?,
    val failureCategory: FailureCategory?,
) {
    init {
        require((status == AuthorizationViewStatus.AUTHORIZED) == (approved != null && approvedAt != null)) { "approved terms exist exactly for AUTHORIZED" }
        require((status == AuthorizationViewStatus.FAILED) == (failureCategory != null)) { "a failure category exists exactly for FAILED" }
    }
}
