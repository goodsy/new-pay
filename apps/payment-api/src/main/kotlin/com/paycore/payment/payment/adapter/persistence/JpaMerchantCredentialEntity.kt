package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * merchant_api_credential 행(V5, D-CPA-001 투영). **읽기 전용**: main 코드에는 쓰기 경로가 없고(투영 적용은 MASTER-CREDENTIAL-001) runtime 권한도
 * SELECT뿐이다. 값 컬럼은 `insertable = false`·`updatable = false`라 변경 감지로 UPDATE SQL이 만들어지지 않는다(권한 1142 이전에 막힘).
 * 원문 키는 없고 검증값(SHA-256)만 있다. 2차·쿼리 캐시를 쓰지 않는다(폐기 즉시성).
 */
@Entity
@Immutable
@Table(name = "merchant_api_credential")
class JpaMerchantCredentialEntity protected constructor() {
    @field:Id
    @field:Column(name = "key_id", nullable = false, updatable = false, length = 32)
    lateinit var keyId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, insertable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "environment", nullable = false, insertable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "secret_sha256", nullable = false, insertable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var secretSha256: String
        protected set

    @field:Column(name = "status", nullable = false, insertable = false, updatable = false, length = 16)
    lateinit var status: String
        protected set

    @field:Column(name = "valid_from", nullable = false, insertable = false, updatable = false)
    lateinit var validFrom: Instant
        protected set

    @field:Column(name = "valid_until", insertable = false, updatable = false)
    var validUntil: Instant? = null
        protected set

    @field:Column(name = "lease_until", nullable = false, insertable = false, updatable = false)
    lateinit var leaseUntil: Instant
        protected set

    @field:Column(name = "revision", nullable = false, insertable = false, updatable = false)
    var revision: Long = 0
        protected set

    @field:Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    lateinit var updatedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return keyId == (other as JpaMerchantCredentialEntity).keyId
    }

    override fun hashCode(): Int = keyId.hashCode()

    /** 검증값을 넣지 않는다. */
    override fun toString(): String = "JpaMerchantCredentialEntity(keyId=$keyId, status=$status, revision=$revision)"
}
