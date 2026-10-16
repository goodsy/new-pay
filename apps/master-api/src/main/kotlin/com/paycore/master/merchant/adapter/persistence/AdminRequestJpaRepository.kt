package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface AdminRequestJpaRepository : JpaRepository<JpaAdminRequestEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from JpaAdminRequestEntity r where r.operation = :operation and r.idempotencyKey = :idempotencyKey")
    fun lockByScope(@Param("operation") operation: String, @Param("idempotencyKey") idempotencyKey: String): JpaAdminRequestEntity?

    /** 범위 UNIQUE에 예약(PENDING)을 삽입하거나, 이미 있으면 바꾸지 않고 그 행의 잠금을 기다려 잡는다(SELECT 후 INSERT 아님). */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO admin_request (operation, idempotency_key, request_record_id, request_fingerprint, actor_ref, result_kind,
                result_code, result_reference, created_at, updated_at, version)
            VALUES (:operation, :idempotencyKey, :requestRecordId, :requestFingerprint, :actorRef, 'PENDING', NULL, NULL, :at, :at, 0)
            ON DUPLICATE KEY UPDATE version = version
        """,
    )
    @Suppress("LongParameterList")
    fun insertPendingIfAbsent(
        @Param("operation") operation: String,
        @Param("idempotencyKey") idempotencyKey: String,
        @Param("requestRecordId") requestRecordId: String,
        @Param("requestFingerprint") requestFingerprint: String,
        @Param("actorRef") actorRef: String,
        @Param("at") at: Instant,
    ): Int
}
