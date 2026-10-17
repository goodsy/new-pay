package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.domain.CheckoutTokenId
import com.paycore.payment.payment.domain.MerchantReturnUrl
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import java.time.Instant

/** 결제창 토큰 상태(AUTHN-001 §10.1). ISSUED만 교환할 수 있다. CLOSED = Payment가 REQUESTED가 아니거나 결제창 시각이 지나 닫음. */
enum class CheckoutTokenStatus { ISSUED, CONSUMED, SUPERSEDED, EXPIRED, CLOSED }

/**
 * 결제창 1회용 토큰 기록(D-AUTHN-001, 보안 프로토콜 기록 — Aggregate 아님, AN-14). 원문은 없고 digest만. 바인딩(Payment·주문·Site·금액·통화)과 검증된 반환
 * URL·Site host·Snapshot 버전·발급 Credential·정책 버전을 보존한다. 불변식은 DB CHECK와 같다.
 */
data class CheckoutTokenRecord(
    val id: CheckoutTokenId,
    val digest: SecretDigest,
    val paymentId: PaymentId,
    val orderKey: OrderKey,
    val merchantSiteId: MerchantSiteId,
    val money: Money,
    val successUrl: MerchantReturnUrl,
    val failUrl: MerchantReturnUrl,
    val siteHost: String,
    val siteSnapshotVersion: String,
    val status: CheckoutTokenStatus,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val consumedAt: Instant?,
    val closedAt: Instant?,
    val issuedByCredentialId: CredentialKeyId,
    val policyVersion: String,
) {
    init {
        require(digest.kind == SecretKind.CHECKOUT_TOKEN) { "a checkout token digest is required" }
        require(expiresAt.isAfter(issuedAt)) { "a token expires after it is issued" }
        require((status == CheckoutTokenStatus.CONSUMED) == (consumedAt != null)) { "consumedAt exists exactly for CONSUMED tokens" }
        require((status in CLOSED_STATUSES) == (closedAt != null)) { "closedAt exists exactly for SUPERSEDED/EXPIRED/CLOSED tokens" }
        require(successUrl.hasHost(siteHost) && failUrl.hasHost(siteHost)) { "return URLs must have the site host" }
        require(siteSnapshotVersion.isNotEmpty() && policyVersion.isNotEmpty()) { "versions must not be empty" }
    }

    fun isExchangeable(now: Instant): Boolean = status == CheckoutTokenStatus.ISSUED && now.isBefore(expiresAt)

    fun consumed(at: Instant): CheckoutTokenRecord {
        check(status == CheckoutTokenStatus.ISSUED) { "only an ISSUED token can be consumed" }
        return copy(status = CheckoutTokenStatus.CONSUMED, consumedAt = at)
    }

    fun closed(target: CheckoutTokenStatus, at: Instant): CheckoutTokenRecord {
        require(target in CLOSED_STATUSES) { "close target must be SUPERSEDED, EXPIRED or CLOSED" }
        check(status == CheckoutTokenStatus.ISSUED) { "only an ISSUED token can be closed" }
        return copy(status = target, closedAt = at)
    }

    override fun toString(): String = "CheckoutTokenRecord(id=${id.value}, paymentId=${paymentId.value}, status=$status)"

    private companion object {
        val CLOSED_STATUSES = setOf(CheckoutTokenStatus.SUPERSEDED, CheckoutTokenStatus.EXPIRED, CheckoutTokenStatus.CLOSED)
    }
}

/**
 * 결제창 토큰 Store(CONVENTIONS §14 Store ③ — 행 식별자가 있지만 Aggregate가 아닌 보안 프로토콜 기록). TX 없이 호출하면 거부한다(MANDATORY). 잠금 순서
 * … → payment → checkout_session → checkout_token(§10.3): 토큰은 digest로 비잠금 조회해 payment를 얻은 뒤 순서대로 잠근다.
 */
interface CheckoutTokenStore {
    /** INSERT(persist만). digest·미교환(Payment당 ISSUED 1) UNIQUE 충돌은 [PersistenceFailure.UniqueConflict]. 최초 version을 돌려준다. */
    fun insert(token: CheckoutTokenRecord): Long

    /** digest로 비잠금 조회(교환 첫 단계). */
    fun findByDigest(digest: SecretDigest): CheckoutTokenRecord?

    fun lock(id: CheckoutTokenId): Versioned<CheckoutTokenRecord>?

    /** 같은 Payment의 ISSUED 토큰(최대 1)을 잠가 읽는다. */
    fun lockIssuedOfPayment(paymentId: PaymentId): Versioned<CheckoutTokenRecord>?

    /** 상태(소비·닫힘)만 반영한다. 바인딩·URL은 바꾸지 않는다. 새 version을 돌려준다. */
    fun update(token: CheckoutTokenRecord, expectedVersion: Long): Long
}
