package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * idempotency_request 행. 최초 INSERT는 adapter의 native `INSERT … ON DUPLICATE KEY`(같은 키 동시 요청 직렬화)이고, JPA는 잠가 읽고
 * 결과·갱신을 반영한다(변경 감지 + `@Version`). 범위 컬럼(environment, MID, operation, key)은 바뀌지 않는다.
 */
@Entity
@Table(name = "idempotency_request")
class JpaIdempotencyRequestEntity protected constructor() {
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long = 0
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "operation", nullable = false, updatable = false, length = 40)
    lateinit var operation: String
        protected set

    @field:Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    lateinit var idempotencyKey: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, length = 64)
    lateinit var requestRecordId: String

    @field:Column(name = "request_fingerprint", nullable = false, length = 80)
    lateinit var requestFingerprint: String

    @field:Column(name = "result_kind", nullable = false, length = 16)
    lateinit var resultKind: String

    @field:Column(name = "result_code", length = 48)
    var resultCode: String? = null

    @field:Column(name = "result_payment_id", length = 64)
    var resultPaymentId: String? = null

    @field:Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @field:Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return id == (other as JpaIdempotencyRequestEntity).id
    }

    override fun hashCode(): Int = id.hashCode()

    /** 멱등키 값은 넣지 않는다(가맹점 입력, 로그 노출 최소화). */
    override fun toString(): String = "JpaIdempotencyRequestEntity(id=$id, resultKind=$resultKind, version=$version)"
}
