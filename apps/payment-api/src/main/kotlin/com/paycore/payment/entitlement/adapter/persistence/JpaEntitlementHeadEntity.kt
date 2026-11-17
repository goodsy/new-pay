package com.paycore.payment.entitlement.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/** entitlement_head 행. 최초 INSERT는 native IODKU(빈 자리), 갱신은 변경 감지 + `@Version`. */
@Entity
@Table(name = "entitlement_head")
class JpaEntitlementHeadEntity protected constructor() {
    @field:Id
    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "revision", nullable = false)
    var revision: Long = 0

    @field:Column(name = "content_hash", length = 80)
    var contentHash: String? = null

    @field:Column(name = "schema_version")
    var schemaVersion: Int? = null

    @field:Column(name = "issued_at")
    var issuedAt: Instant? = null

    @field:Column(name = "lease_seq")
    var leaseSeq: Long? = null

    @field:Column(name = "lease_until")
    var leaseUntil: Instant? = null

    @field:Column(name = "applied_at")
    var appliedAt: Instant? = null

    @field:Column(name = "source_event_id", columnDefinition = "char(36)")
    var sourceEventId: String? = null

    @field:Column(name = "blocked_reason", length = 32)
    var blockedReason: String? = null

    @field:Column(name = "blocked_revision")
    var blockedRevision: Long? = null

    /**
     * MERCHANT-CODE-001 MC-4(V36): 적용한 v4 Snapshot의 가맹점 코드(v1~v3 head·빈 head는 NULL). 일반 갱신 열 — head는 적용마다 전체 상태를 바꾸는 투영이라
     * `updatable = false`가 아니다(코드 불변식은 MASTER 원본·PAYMENT 무결성 규칙·경보가 지킨다). 변경 감지 + `@Version`으로 같은 UPDATE 문에 실린다.
     */
    @field:Column(name = "merchant_code", length = 14)
    var merchantCode: String? = null

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && merchantId == (other as JpaEntitlementHeadEntity).merchantId)

    override fun hashCode(): Int = merchantId.hashCode()

    override fun toString(): String = "JpaEntitlementHeadEntity(merchantId=$merchantId, revision=$revision, blocked=$blockedReason)"
}
