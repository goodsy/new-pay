package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * admin_request 행. 최초 INSERT는 native `INSERT … ON DUPLICATE KEY UPDATE`(같은 키 동시 요청 직렬화), JPA는 잠가 읽고 결과를 반영한다
 * (변경 감지 + `@Version`). 범위 컬럼(operation, key)은 바뀌지 않는다. 멱등키 값은 toString에 넣지 않는다.
 */
@Entity
@Table(name = "admin_request")
class JpaAdminRequestEntity protected constructor() {
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long = 0
        protected set

    @field:Column(name = "operation", nullable = false, updatable = false, length = 48)
    lateinit var operation: String
        protected set

    @field:Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    lateinit var idempotencyKey: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "request_fingerprint", nullable = false, updatable = false, length = 80)
    lateinit var requestFingerprint: String
        protected set

    @field:Column(name = "actor_ref", nullable = false, updatable = false, length = 64)
    lateinit var actorRef: String
        protected set

    @field:Column(name = "result_kind", nullable = false, length = 16)
    lateinit var resultKind: String

    @field:Column(name = "result_code", length = 48)
    var resultCode: String? = null

    @field:Column(name = "result_reference", length = 64)
    var resultReference: String? = null

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && id == (other as JpaAdminRequestEntity).id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "JpaAdminRequestEntity(id=$id, operation=$operation, resultKind=$resultKind, version=$version)"
}
