package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.Instant

/** site_activation_confirmation 행(append-only). */
@Entity
@Table(name = "site_activation_confirmation")
class JpaSiteActivationConfirmationEntity protected constructor() {
    @field:Id
    @field:Column(name = "confirmation_id", nullable = false, updatable = false, length = 64)
    lateinit var confirmationId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_site_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantSiteId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "payment_method", nullable = false, updatable = false, length = 16)
    lateinit var paymentMethod: String
        protected set

    @field:Column(name = "activation_id", nullable = false, updatable = false, length = 64)
    lateinit var activationId: String
        protected set

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
        protected set

    @field:Column(name = "confirmed_at", nullable = false, updatable = false)
    lateinit var confirmedAt: Instant
        protected set

    @field:Column(name = "confirmed_by", nullable = false, updatable = false, length = 64)
    lateinit var confirmedBy: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "confirmation_seq", nullable = false, updatable = false)
    var confirmationSeq: Int = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && confirmationId == (other as JpaSiteActivationConfirmationEntity).confirmationId)

    override fun hashCode(): Int = confirmationId.hashCode()

    override fun toString(): String = "JpaSiteActivationConfirmationEntity(confirmationId=$confirmationId)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            accountId: String,
            siteId: String,
            merchantId: String,
            method: String,
            activationId: String,
            evidenceRef: String,
            at: Instant,
            by: String,
            requestRecordId: String,
            seq: Int,
        ) = JpaSiteActivationConfirmationEntity().also {
            it.confirmationSeq = seq
            it.confirmationId = id
            it.merchantAccountId = accountId
            it.merchantSiteId = siteId
            it.merchantId = merchantId
            it.paymentMethod = method
            it.activationId = activationId
            it.evidenceRef = evidenceRef
            it.confirmedAt = at
            it.confirmedBy = by
            it.requestRecordId = requestRecordId
        }
    }
}
