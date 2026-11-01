package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * demo_credential_issuance 행(V29, DEMO-SEED-001 §4.5). **INSERT 전용**(`@Immutable` — 변경 감지 UPDATE 없음, runtime 권한도 SELECT·INSERT). 고정 id 1.
 * `Persistable`을 구현하지 않고 Spring Data `save`를 쓰지 않는다: 할당 ID의 `save`는 `merge`라 이미 있는 행을 읽어 조용히 끝나 1회성 PK 검사가 사라진다
 * (변이 C5) — adapter는 `EntityManager.persist` + `flush`로 1062를 드러낸다.
 */
@Entity
@Immutable
@Table(name = "demo_credential_issuance")
class JpaDemoCredentialIssuanceEntity protected constructor() {
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Byte = 0
        protected set

    @field:Column(name = "key_id", nullable = false, updatable = false, length = 32)
    lateinit var keyId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "issued_at", nullable = false, updatable = false)
    lateinit var issuedAt: Instant
        protected set

    @field:Column(name = "actor", nullable = false, updatable = false, length = 64)
    lateinit var actor: String
        protected set

    @field:Column(name = "correlation_id", nullable = false, updatable = false, length = 64)
    lateinit var correlationId: String
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return id == (other as JpaDemoCredentialIssuanceEntity).id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "JpaDemoCredentialIssuanceEntity(id=$id, keyId=$keyId)"

    companion object {
        /** 1회성: 행은 많아야 하나(DB `CHECK (id = 1)`). */
        const val SINGLE_ID: Byte = 1

        fun issued(keyId: String, merchantAccountId: String, issuedAt: Instant, actor: String, correlationId: String) =
            JpaDemoCredentialIssuanceEntity().apply {
                this.id = SINGLE_ID
                this.keyId = keyId
                this.merchantAccountId = merchantAccountId
                this.environment = "TEST"
                this.issuedAt = issuedAt
                this.actor = actor
                this.correlationId = correlationId
            }
    }
}
