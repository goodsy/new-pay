package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.CheckoutHandle
import com.paycore.payment.payment.domain.Environment
import java.time.Instant

/**
 * 결제창 화면 행(jOOQ 조회 — 비권위, AUTHN-001 §6.2·§16): checkout_session + payment. 판정·상태 변경에 쓰지 않는다(POST 처리가 잠금 TX에서 다시 판정한다).
 * [sessionDigest]는 저장된 `cks1:` digest 문자열(쿠키 대조용), [failUrl]은 저장된 검증 URL 원문.
 */
data class CheckoutViewRow(
    val checkoutSessionId: String,
    val sessionDigest: String,
    val sessionStatus: String,
    val sessionCloseReason: String?,
    val sessionExpiresAt: Instant,
    val paymentId: String,
    val paymentStatus: String,
    val checkoutExpiresAt: Instant?,
    val environment: Environment,
    val merchantAccountId: String,
    val merchantSiteId: String,
    val orderName: String?,
    val amountMinor: Long,
    val currency: String,
    val siteHost: String,
    val failUrl: String,
) {
    override fun toString(): String = "CheckoutViewRow(checkoutSessionId=$checkoutSessionId, sessionStatus=$sessionStatus, paymentStatus=$paymentStatus)"
}

/** 결제창 화면 조회 Port(CONVENTIONS §14 jOOQ 조회 `…Reader`). 읽기 전용 TX 하나. */
interface CheckoutViewReader {
    fun findByHandle(handle: CheckoutHandle): CheckoutViewRow?
}
