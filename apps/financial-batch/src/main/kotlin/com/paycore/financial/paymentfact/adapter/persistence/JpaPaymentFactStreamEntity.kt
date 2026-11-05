package com.paycore.financial.paymentfact.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/**
 * payment_fact_stream 행(결제별 직렬화 — 투영 사실 표들의 FK 부모). 신원 열은 첫 사실이 IODKU로 만들 때 정해지고 바뀌지 않는다(updatable=false).
 * 반영 건수·갱신 시각만 잠가 읽은 Entity의 변경 감지로 바꾸고 `@Version`이 함께 오른다(잠금 밖 stale 객체가 덮어쓰는 결함의 두 번째 방어).
 */
@Entity
@Table(name = "payment_fact_stream")
class JpaPaymentFactStreamEntity protected constructor() {
    @field:Id
    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 8)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_site_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantSiteId: String
        protected set

    @field:Column(name = "merchant_order_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantOrderId: String
        protected set

    @field:Column(name = "fact_count", nullable = false)
    var factCount: Int = 0

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaPaymentFactStreamEntity(paymentId=$paymentId, factCount=$factCount, version=$version)"
}
