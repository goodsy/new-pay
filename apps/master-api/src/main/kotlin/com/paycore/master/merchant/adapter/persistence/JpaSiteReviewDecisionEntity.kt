package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.io.Serializable
import java.time.Instant

/** (merchant_site_id, seq) 복합 키. */
@Embeddable
class SiteReviewKey protected constructor() : Serializable {
    @field:Column(name = "merchant_site_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantSiteId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is SiteReviewKey && merchantSiteId == other.merchantSiteId && seq == other.seq)

    override fun hashCode(): Int = 31 * merchantSiteId.hashCode() + seq

    override fun toString(): String = "SiteReviewKey($merchantSiteId#$seq)"

    companion object {
        fun of(siteId: String, seq: Int) = SiteReviewKey().also {
            it.merchantSiteId = siteId
            it.seq = seq
        }
    }
}

/** merchant_site_review_decision 행(append-only). */
@Entity
@Table(name = "merchant_site_review_decision")
class JpaSiteReviewDecisionEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: SiteReviewKey
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "decision", nullable = false, updatable = false, length = 16)
    lateinit var decision: String
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 200)
    lateinit var reason: String
        protected set

    @field:Column(name = "evidence_ref", updatable = false, length = 128)
    var evidenceRef: String? = null
        protected set

    @field:Column(name = "decided_at", nullable = false, updatable = false)
    lateinit var decidedAt: Instant
        protected set

    @field:Column(name = "decided_by", nullable = false, updatable = false, length = 64)
    lateinit var decidedBy: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaSiteReviewDecisionEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaSiteReviewDecisionEntity(key=$key, decision=$decision)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            key: SiteReviewKey,
            merchantId: String,
            decision: String,
            reason: String,
            evidenceRef: String?,
            at: Instant,
            by: String,
            requestRecordId: String,
        ) = JpaSiteReviewDecisionEntity().also {
            it.key = key
            it.merchantId = merchantId
            it.decision = decision
            it.reason = reason
            it.evidenceRef = evidenceRef
            it.decidedAt = at
            it.decidedBy = by
            it.requestRecordId = requestRecordId
        }
    }
}
