package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/** merchant_webhook_attempt 행(WEBHOOK-001 V25 — append-only 전송 증거). `@Immutable` + runtime 권한 SELECT·INSERT(1142) 두 겹. URL·본문·서명 값 열이 없다. */
@Entity
@Immutable
@Table(name = "merchant_webhook_attempt")
class JpaMerchantWebhookAttemptEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "attempt_id", nullable = false, updatable = false)
    var attemptId: Long? = null
        protected set

    @field:Column(name = "delivery_id", nullable = false, updatable = false, columnDefinition = "char(30)")
    lateinit var deliveryId: String
        protected set

    @field:Column(name = "attempt_no", nullable = false, updatable = false)
    var attemptNo: Int = 0
        protected set

    @field:Column(name = "worker", nullable = false, updatable = false, length = 64)
    lateinit var worker: String
        protected set

    @field:Column(name = "endpoint_ref", updatable = false, length = 64)
    var endpointRef: String? = null
        protected set

    @field:Column(name = "signing_key_ids", updatable = false, length = 96)
    var signingKeyIds: String? = null
        protected set

    @field:Column(name = "outcome", nullable = false, updatable = false, length = 48)
    lateinit var outcome: String
        protected set

    @field:Column(name = "http_status", updatable = false)
    var httpStatus: Short? = null
        protected set

    @field:Column(name = "schedule_version", nullable = false, updatable = false, length = 64)
    lateinit var scheduleVersion: String
        protected set

    @field:Column(name = "started_at", nullable = false, updatable = false)
    lateinit var startedAt: Instant
        protected set

    @field:Column(name = "finished_at", nullable = false, updatable = false)
    lateinit var finishedAt: Instant
        protected set

    private fun naturalKey() = deliveryId to attemptNo

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return naturalKey() == (other as JpaMerchantWebhookAttemptEntity).naturalKey()
    }

    override fun hashCode(): Int = naturalKey().hashCode()

    override fun toString(): String = "JpaMerchantWebhookAttemptEntity(deliveryId=$deliveryId, attemptNo=$attemptNo, outcome=$outcome)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            deliveryId: String, attemptNo: Int, worker: String, endpointRef: String?, signingKeyIds: String?, outcome: String, httpStatus: Int?, scheduleVersion: String,
            startedAt: Instant, finishedAt: Instant,
        ) = JpaMerchantWebhookAttemptEntity().also {
            it.deliveryId = deliveryId
            it.attemptNo = attemptNo
            it.worker = worker
            it.endpointRef = endpointRef
            it.signingKeyIds = signingKeyIds
            it.outcome = outcome
            it.httpStatus = httpStatus?.toShort()
            it.scheduleVersion = scheduleVersion
            it.startedAt = startedAt
            it.finishedAt = finishedAt
        }
    }
}
