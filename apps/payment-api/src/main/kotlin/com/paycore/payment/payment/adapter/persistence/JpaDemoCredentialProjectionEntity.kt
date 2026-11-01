package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * merchant_api_credential 행의 **INSERT 전용** 매핑(DEMO-SEED-001 §4.5 — Demo API key). 같은 표의 읽기 매핑 [JpaMerchantCredentialEntity]는 그대로 둔다
 * (인증 경로 무변경 — M3). 모든 열 `insertable = true`·`updatable = false` + `@Immutable`(UPDATE 없음 — runtime 권한도 UPDATE·DELETE 없음). 열 정의는 V5와
 * 같다(`ddl-auto=validate`는 열마다 검사한다). `Persistable` 미구현·Spring Data `save` 미사용(merge 금지 — C5). 2차 캐시 없음(기존 관례).
 */
@Entity
@Immutable
@Table(name = "merchant_api_credential")
class JpaDemoCredentialProjectionEntity protected constructor() {
    @field:Id
    @field:Column(name = "key_id", nullable = false, updatable = false, length = 32)
    lateinit var keyId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "secret_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var secretSha256: String
        protected set

    @field:Column(name = "status", nullable = false, updatable = false, length = 16)
    lateinit var status: String
        protected set

    @field:Column(name = "valid_from", nullable = false, updatable = false)
    lateinit var validFrom: Instant
        protected set

    @field:Column(name = "valid_until", updatable = false)
    var validUntil: Instant? = null
        protected set

    @field:Column(name = "lease_until", nullable = false, updatable = false)
    lateinit var leaseUntil: Instant
        protected set

    @field:Column(name = "revision", nullable = false, updatable = false)
    var revision: Long = 0
        protected set

    @field:Column(name = "updated_at", nullable = false, updatable = false)
    lateinit var updatedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return keyId == (other as JpaDemoCredentialProjectionEntity).keyId
    }

    override fun hashCode(): Int = keyId.hashCode()

    /** 검증값을 넣지 않는다. */
    override fun toString(): String = "JpaDemoCredentialProjectionEntity(keyId=$keyId, status=$status, revision=$revision)"

    companion object {
        /** Demo 키: environment TEST·status ACTIVE·revision 1(D-DEMO-002·004). */
        fun demoTestKey(keyId: String, merchantAccountId: String, secretSha256: String, validFrom: Instant, validUntil: Instant, leaseUntil: Instant) =
            JpaDemoCredentialProjectionEntity().apply {
                this.keyId = keyId
                this.merchantAccountId = merchantAccountId
                this.environment = "TEST"
                this.secretSha256 = secretSha256
                this.status = "ACTIVE"
                this.validFrom = validFrom
                this.validUntil = validUntil
                this.leaseUntil = leaseUntil
                this.revision = 1
                this.updatedAt = validFrom
            }
    }
}
