package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * checkout_session 행(AUTHN-001 §10.1). 바뀌는 컬럼은 상태·종료 사유·종료 시각뿐이다. 생성 컬럼(active_payment_id)은 매핑하지 않는다.
 * toString에 digest·handle·URL을 넣지 않는다.
 */
@Entity
@Table(name = "checkout_session")
class JpaCheckoutSessionEntity protected constructor() {
    @field:Id
    @field:Column(name = "checkout_session_id", nullable = false, updatable = false, length = 64)
    lateinit var checkoutSessionId: String
        protected set

    @field:Column(name = "handle", nullable = false, updatable = false, columnDefinition = "char(22)")
    lateinit var handle: String
        protected set

    @field:Column(name = "session_digest", nullable = false, updatable = false, columnDefinition = "char(69)")
    lateinit var sessionDigest: String
        protected set

    @field:Column(name = "checkout_token_id", nullable = false, updatable = false, length = 64)
    lateinit var checkoutTokenId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
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

    @field:Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @field:Column(name = "close_reason", length = 24)
    var closeReason: String? = null

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "expires_at", nullable = false, updatable = false)
    lateinit var expiresAt: Instant
        protected set

    @field:Column(name = "closed_at")
    var closedAt: Instant? = null

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
        return checkoutSessionId == (other as JpaCheckoutSessionEntity).checkoutSessionId
    }

    override fun hashCode(): Int = checkoutSessionId.hashCode()

    override fun toString(): String = "JpaCheckoutSessionEntity(checkoutSessionId=$checkoutSessionId, status=$status, version=$version)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            checkoutSessionId: String, handle: String, sessionDigest: String, checkoutTokenId: String, paymentId: String, successUrl: String, failUrl: String,
            siteHost: String, status: String, closeReason: String?, createdAt: Instant, expiresAt: Instant, closedAt: Instant?, policyVersion: String,
        ): JpaCheckoutSessionEntity = JpaCheckoutSessionEntity().also {
            it.checkoutSessionId = checkoutSessionId
            it.handle = handle
            it.sessionDigest = sessionDigest
            it.checkoutTokenId = checkoutTokenId
            it.paymentId = paymentId
            it.successUrl = successUrl
            it.failUrl = failUrl
            it.siteHost = siteHost
            it.status = status
            it.closeReason = closeReason
            it.createdAt = createdAt
            it.expiresAt = expiresAt
            it.closedAt = closedAt
            it.policyVersion = policyVersion
        }
    }
}
