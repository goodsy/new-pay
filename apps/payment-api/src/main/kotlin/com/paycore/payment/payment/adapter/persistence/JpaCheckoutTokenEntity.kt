package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * checkout_token 행(AUTHN-001 §10.1). Entity 공통 선택은 [JpaPaymentEntity] 설명과 같다(data class 아님, 식별자 equals, 생성 시 불변 컬럼 `updatable = false`).
 * 바뀌는 컬럼은 상태·소비/닫힘 시각뿐이다. 생성 컬럼(outstanding_payment_id)은 매핑하지 않는다 — DB가 계산하고 UNIQUE로 지킨다.
 * toString에 digest·URL을 넣지 않는다.
 */
@Entity
@Table(name = "checkout_token")
class JpaCheckoutTokenEntity protected constructor() {
    @field:Id
    @field:Column(name = "checkout_token_id", nullable = false, updatable = false, length = 64)
    lateinit var checkoutTokenId: String
        protected set

    @field:Column(name = "token_digest", nullable = false, updatable = false, columnDefinition = "char(69)")
    lateinit var tokenDigest: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_order_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantOrderId: String
        protected set

    @field:Column(name = "merchant_site_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantSiteId: String
        protected set

    @field:Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 0
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currency: String
        protected set

    @field:Column(name = "success_url", nullable = false, updatable = false, length = 2048)
    lateinit var successUrl: String
        protected set

    @field:Column(name = "fail_url", nullable = false, updatable = false, length = 2048)
    lateinit var failUrl: String
        protected set

    @field:Column(name = "site_host", nullable = false, updatable = false, length = 253)
    lateinit var siteHost: String
        protected set

    @field:Column(name = "site_snapshot_version", nullable = false, updatable = false, length = 64)
    lateinit var siteSnapshotVersion: String
        protected set

    @field:Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @field:Column(name = "issued_at", nullable = false, updatable = false)
    lateinit var issuedAt: Instant
        protected set

    @field:Column(name = "expires_at", nullable = false, updatable = false)
    lateinit var expiresAt: Instant
        protected set

    @field:Column(name = "consumed_at")
    var consumedAt: Instant? = null

    @field:Column(name = "closed_at")
    var closedAt: Instant? = null

    @field:Column(name = "issued_by_credential_id", nullable = false, updatable = false, length = 32)
    lateinit var issuedByCredentialId: String
        protected set

    @field:Column(name = "policy_version", nullable = false, updatable = false, length = 64)
    lateinit var policyVersion: String
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return checkoutTokenId == (other as JpaCheckoutTokenEntity).checkoutTokenId
    }

    override fun hashCode(): Int = checkoutTokenId.hashCode()

    override fun toString(): String = "JpaCheckoutTokenEntity(checkoutTokenId=$checkoutTokenId, status=$status, version=$version)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            checkoutTokenId: String, tokenDigest: String, paymentId: String, environment: String, merchantAccountId: String, merchantOrderId: String,
            merchantSiteId: String, amountMinor: Long, currency: String, successUrl: String, failUrl: String, siteHost: String, siteSnapshotVersion: String,
            status: String, issuedAt: Instant, expiresAt: Instant, consumedAt: Instant?, closedAt: Instant?, issuedByCredentialId: String, policyVersion: String,
        ): JpaCheckoutTokenEntity = JpaCheckoutTokenEntity().also {
            it.checkoutTokenId = checkoutTokenId
            it.tokenDigest = tokenDigest
            it.paymentId = paymentId
            it.environment = environment
            it.merchantAccountId = merchantAccountId
            it.merchantOrderId = merchantOrderId
            it.merchantSiteId = merchantSiteId
            it.amountMinor = amountMinor
            it.currency = currency
            it.successUrl = successUrl
            it.failUrl = failUrl
            it.siteHost = siteHost
            it.siteSnapshotVersion = siteSnapshotVersion
            it.status = status
            it.issuedAt = issuedAt
            it.expiresAt = expiresAt
            it.consumedAt = consumedAt
            it.closedAt = closedAt
            it.issuedByCredentialId = issuedByCredentialId
            it.policyVersion = policyVersion
        }
    }
}
