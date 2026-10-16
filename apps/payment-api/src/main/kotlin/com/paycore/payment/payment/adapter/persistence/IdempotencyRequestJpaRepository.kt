package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface IdempotencyRequestJpaRepository : JpaRepository<JpaIdempotencyRequestEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select r from JpaIdempotencyRequestEntity r where r.environment = :environment and r.merchantAccountId = :merchantAccountId " +
            "and r.operation = :operation and r.idempotencyKey = :idempotencyKey",
    )
    fun lockByScope(
        @Param("environment") environment: String,
        @Param("merchantAccountId") merchantAccountId: String,
        @Param("operation") operation: String,
        @Param("idempotencyKey") idempotencyKey: String,
    ): JpaIdempotencyRequestEntity?

    /** 범위 UNIQUE에 예약(PENDING)을 삽입하거나, 이미 있으면 바꾸지 않고 그 행의 잠금을 기다려 잡는다. */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO idempotency_request (environment, merchant_account_id, operation, idempotency_key, request_record_id,
                request_fingerprint, result_kind, result_code, result_payment_id, created_at, expires_at, updated_at, version)
            VALUES (:environment, :merchantAccountId, :operation, :idempotencyKey, :requestRecordId,
                :requestFingerprint, 'PENDING', NULL, NULL, :createdAt, :expiresAt, :createdAt, 0)
            ON DUPLICATE KEY UPDATE version = version
        """,
    )
    fun insertPendingIfAbsent(
        @Param("environment") environment: String,
        @Param("merchantAccountId") merchantAccountId: String,
        @Param("operation") operation: String,
        @Param("idempotencyKey") idempotencyKey: String,
        @Param("requestRecordId") requestRecordId: String,
        @Param("requestFingerprint") requestFingerprint: String,
        @Param("createdAt") createdAt: Instant,
        @Param("expiresAt") expiresAt: Instant,
    ): Int
}
