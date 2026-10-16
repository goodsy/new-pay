package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * payment_method_activation 행(D-MD-017, D-MS-003). 상태 전이는 잠가 읽은 Entity의 status·evidence_ref·status_changed_at 변경을 flush 때
 * `UPDATE … WHERE version = ?`로 반영한다(변경 감지 + `@Version`). 소속·수단·등록 컬럼은 updatable=false. 생성 컬럼 open_method_key는 매핑하지 않는다.
 */
@Entity
@Table(name = "payment_method_activation")
class JpaPaymentMethodActivationEntity protected constructor() {
    @field:Id
    @field:Column(name = "activation_id", nullable = false, updatable = false, length = 64)
    lateinit var activationId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "payment_method", nullable = false, updatable = false, length = 16)
    lateinit var paymentMethod: String
        protected set

    @field:Column(name = "registered_at", nullable = false, updatable = false)
    lateinit var registeredAt: Instant
        protected set

    @field:Column(name = "registered_by", nullable = false, updatable = false, length = 64)
    lateinit var registeredBy: String
        protected set

    @field:Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @field:Column(name = "evidence_ref", length = 128)
    var evidenceRef: String? = null

    @field:Column(name = "status_changed_at", nullable = false)
    lateinit var statusChangedAt: Instant

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && activationId == (other as JpaPaymentMethodActivationEntity).activationId)

    override fun hashCode(): Int = activationId.hashCode()

    override fun toString(): String = "JpaPaymentMethodActivationEntity(activationId=$activationId, paymentMethod=$paymentMethod, status=$status)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(id: String, accountId: String, method: String, at: Instant, by: String, status: String, evidenceRef: String?) =
            JpaPaymentMethodActivationEntity().also {
                it.activationId = id
                it.merchantAccountId = accountId
                it.paymentMethod = method
                it.registeredAt = at
                it.registeredBy = by
                it.status = status
                it.evidenceRef = evidenceRef
                it.statusChangedAt = at
            }
    }
}
