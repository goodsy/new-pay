package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.Instant

/** contract_service_agreement 행(INSERT만). */
@Entity
@Table(name = "contract_service_agreement")
class JpaContractServiceAgreementEntity protected constructor() {
    @field:Id
    @field:Column(name = "agreement_id", nullable = false, updatable = false, length = 64)
    lateinit var agreementId: String
        protected set

    @field:Column(name = "contract_id", nullable = false, updatable = false, length = 64)
    lateinit var contractId: String
        protected set

    @field:Column(name = "service_kind", nullable = false, updatable = false, length = 32)
    lateinit var serviceKind: String
        protected set

    @field:Column(name = "added_at", nullable = false, updatable = false)
    lateinit var addedAt: Instant
        protected set

    @field:Column(name = "added_by", nullable = false, updatable = false, length = 64)
    lateinit var addedBy: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && agreementId == (other as JpaContractServiceAgreementEntity).agreementId)

    override fun hashCode(): Int = agreementId.hashCode()

    override fun toString(): String = "JpaContractServiceAgreementEntity(agreementId=$agreementId, serviceKind=$serviceKind)"

    companion object {
        fun newRow(id: String, contractId: String, serviceKind: String, at: Instant, by: String) = JpaContractServiceAgreementEntity().also {
            it.agreementId = id
            it.contractId = contractId
            it.serviceKind = serviceKind
            it.addedAt = at
            it.addedBy = by
        }
    }
}
