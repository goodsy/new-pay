package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.CheckoutHandle
import com.paycore.payment.payment.domain.CheckoutSessionId
import com.paycore.payment.payment.domain.CheckoutTokenId
import com.paycore.payment.payment.domain.MerchantReturnUrl
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import java.time.Instant

enum class CheckoutSessionStatus { ACTIVE, COMPLETED, REVOKED, EXPIRED }

/** 결제창 세션 종료 사유(AUTHN-001 §10.1). 상태와의 짝은 [CheckoutSessionRecord] 불변식(= DB CHECK). */
enum class CheckoutSessionCloseReason(val status: CheckoutSessionStatus) {
    VERIFIED_REDIRECT(CheckoutSessionStatus.COMPLETED),
    CANCELLED(CheckoutSessionStatus.COMPLETED),
    PAYMENT_NOT_AVAILABLE(CheckoutSessionStatus.COMPLETED),
    SUPERSEDED(CheckoutSessionStatus.REVOKED),
    TTL_ELAPSED(CheckoutSessionStatus.EXPIRED),
    PAYMENT_CLOSED(CheckoutSessionStatus.EXPIRED),
}

/**
 * 결제창 브라우저 세션 기록(보안 프로토콜 기록, AN-14). 쿠키 비밀은 digest만, handle은 경로 식별자(판정 m-1). 토큰 1개 = 세션 1개. 반환 URL·Site host는
 * 토큰에서 복사한 값이다. [expiresAt]은 Payment 결제창 시각으로 잘린 값이다(§7.5.1).
 */
data class CheckoutSessionRecord(
    val id: CheckoutSessionId,
    val handle: CheckoutHandle,
    val digest: SecretDigest,
    val tokenId: CheckoutTokenId,
    val paymentId: PaymentId,
    val successUrl: MerchantReturnUrl,
    val failUrl: MerchantReturnUrl,
    val siteHost: String,
    val status: CheckoutSessionStatus,
    val closeReason: CheckoutSessionCloseReason?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val closedAt: Instant?,
    val policyVersion: String,
) {
    init {
        require(digest.kind == SecretKind.CHECKOUT_SESSION) { "a checkout session digest is required" }
        require(expiresAt.isAfter(createdAt)) { "a checkout session expires after it is created" }
        require((status == CheckoutSessionStatus.ACTIVE) == (closeReason == null) && (closeReason == null) == (closedAt == null)) {
            "an ACTIVE session has no close reason/time, a closed one has both"
        }
        require(closeReason == null || closeReason.status == status) { "close reason must match the status" }
        require(successUrl.hasHost(siteHost) && failUrl.hasHost(siteHost)) { "return URLs must have the site host" }
        require(policyVersion.isNotEmpty()) { "policy version must not be empty" }
    }

    fun isUsable(now: Instant): Boolean = status == CheckoutSessionStatus.ACTIVE && now.isBefore(expiresAt)

    fun closed(reason: CheckoutSessionCloseReason, at: Instant): CheckoutSessionRecord {
        check(status == CheckoutSessionStatus.ACTIVE) { "only an ACTIVE checkout session can be closed" }
        return copy(status = reason.status, closeReason = reason, closedAt = at)
    }

    override fun toString(): String = "CheckoutSessionRecord(id=${id.value}, paymentId=${paymentId.value}, status=$status)"
}

/** 결제창 세션 Store(CONVENTIONS §14 Store ③). 잠금 순서 payment → (authentication_session) → checkout_session → checkout_token(§10.3). */
interface CheckoutSessionStore {
    /** INSERT. handle·digest·토큰·ACTIVE(Payment당 1) UNIQUE 충돌은 [PersistenceFailure.UniqueConflict]. */
    fun insert(session: CheckoutSessionRecord): Long

    /** handle로 비잠금 조회(쿠키 digest 대조는 호출자). */
    fun findByHandle(handle: CheckoutHandle): CheckoutSessionRecord?

    fun lock(id: CheckoutSessionId): Versioned<CheckoutSessionRecord>?

    /** 같은 Payment의 ACTIVE 세션(최대 1)을 잠가 읽는다. */
    fun lockActiveOfPayment(paymentId: PaymentId): Versioned<CheckoutSessionRecord>?

    /** 상태(닫힘)만 반영한다. 새 version을 돌려준다. */
    fun update(session: CheckoutSessionRecord, expectedVersion: Long): Long
}
