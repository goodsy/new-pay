package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/** card_route_activation 행. 상태 전이는 변경 감지 + `@Version`. [institutionMerchantRef]는 toString에 넣지 않는다(D-MS2-005). */
@Entity
@Table(name = "card_route_activation")
class JpaCardRouteActivationEntity protected constructor() {
    @field:Id
    @field:Column(name = "activation_id", nullable = false, updatable = false, length = 64)
    lateinit var activationId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "payment_method", nullable = false, updatable = false, length = 16)
    lateinit var paymentMethod: String
        protected set

    @field:Column(name = "acquirer_code", nullable = false, updatable = false, length = 32)
    lateinit var acquirerCode: String
        protected set

    @field:Column(name = "route_ref", nullable = false, updatable = false, length = 64)
    lateinit var routeRef: String
        protected set

    @field:Column(name = "institution_merchant_ref", nullable = false, updatable = false, length = 64)
    lateinit var institutionMerchantRef: String
        protected set

    @field:Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @field:Column(name = "evidence_ref", length = 128)
    var evidenceRef: String? = null

    @field:Column(name = "registered_at", nullable = false, updatable = false)
    lateinit var registeredAt: Instant
        protected set

    @field:Column(name = "registered_by", nullable = false, updatable = false, length = 64)
    lateinit var registeredBy: String
        protected set

    @field:Column(name = "status_changed_at", nullable = false)
    lateinit var statusChangedAt: Instant

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && activationId == (other as JpaCardRouteActivationEntity).activationId)

    override fun hashCode(): Int = activationId.hashCode()

    override fun toString(): String = "JpaCardRouteActivationEntity(id=$activationId, acquirer=$acquirerCode, status=$status)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            accountId: String,
            merchantId: String,
            method: String,
            acquirer: String,
            route: String,
            institutionRef: String,
            status: String,
            at: Instant,
            by: String,
            requestRecordId: String,
        ) = JpaCardRouteActivationEntity().also {
            it.activationId = id
            it.merchantAccountId = accountId
            it.merchantId = merchantId
            it.paymentMethod = method
            it.acquirerCode = acquirer
            it.routeRef = route
            it.institutionMerchantRef = institutionRef
            it.status = status
            it.registeredAt = at
            it.registeredBy = by
            it.statusChangedAt = at
            it.requestRecordId = requestRecordId
        }
    }
}
