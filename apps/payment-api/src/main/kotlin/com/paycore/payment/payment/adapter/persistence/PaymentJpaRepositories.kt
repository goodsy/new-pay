package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/*
 * Spring Data repository(adapter.persistence 전용 — ArchUnit springDataRepositoriesOnlyInPersistenceAdapter). application은 이것을 모르고
 * application.required Port 구현(Jpa*Store)만 안다.
 *
 * 잠금 읽기(@Lock PESSIMISTIC_WRITE → MySQL `SELECT … FOR UPDATE`): 판정에 쓰는 행은 잠가서 최신 커밋 값을 읽는다. READ COMMITTED에서도
 * 잠금 읽기는 다른 TX의 커밋을 기다린 뒤 최신 값을 본다. 최초 INSERT 경쟁 처리는 native `INSERT … ON DUPLICATE KEY UPDATE`(no-op)다:
 * 이미 행이 있으면(다른 TX가 아직 커밋 전이어도) 그 행의 잠금을 기다리고, 없으면 새 행을 넣는다. 목록 SELECT 후 INSERT가 아니다.
 */

interface PaymentJpaRepository : JpaRepository<JpaPaymentEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from JpaPaymentEntity p where p.paymentId = :paymentId")
    fun lockById(@Param("paymentId") paymentId: String): JpaPaymentEntity?
}

interface PaymentTerminationEvidenceJpaRepository : JpaRepository<JpaPaymentTerminationEvidenceEntity, TerminationEvidenceKey> {
    @Query("select e from JpaPaymentTerminationEvidenceEntity e where e.key.paymentId = :paymentId order by e.key.seq")
    fun findByPaymentIdOrdered(@Param("paymentId") paymentId: String): List<JpaPaymentTerminationEvidenceEntity>
}

interface OrderPaymentControlJpaRepository : JpaRepository<JpaOrderPaymentControlEntity, OrderKeyColumns> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from JpaOrderPaymentControlEntity c where c.orderKey = :orderKey")
    fun lockById(@Param("orderKey") orderKey: OrderKeyColumns): JpaOrderPaymentControlEntity?

    /** 행이 없으면 삽입, 있으면 아무것도 바꾸지 않고 그 행의 잠금만 잡는다(최초 insert 경쟁 직렬화). */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO order_payment_control (environment, merchant_account_id, merchant_order_id, fixed_intent_fingerprint,
                current_payment_id, generation, authorized_payment_id, created_at, updated_at, version)
            VALUES (:environment, :merchantAccountId, :merchantOrderId, :fixedIntentFingerprint,
                :currentPaymentId, :generation, NULL, :at, :at, 0)
            ON DUPLICATE KEY UPDATE version = version
        """,
    )
    fun insertIfAbsent(
        @Param("environment") environment: String,
        @Param("merchantAccountId") merchantAccountId: String,
        @Param("merchantOrderId") merchantOrderId: String,
        @Param("fixedIntentFingerprint") fixedIntentFingerprint: String,
        @Param("currentPaymentId") currentPaymentId: String,
        @Param("generation") generation: Long,
        @Param("at") at: Instant,
    ): Int
}

interface IncidentHoldJpaRepository : JpaRepository<JpaIncidentHoldEntity, Long> {
    @Query(
        "select h from JpaIncidentHoldEntity h where h.environment = :environment and h.merchantAccountId = :merchantAccountId " +
            "and h.merchantOrderId = :merchantOrderId order by h.id",
    )
    fun findByOrder(
        @Param("environment") environment: String,
        @Param("merchantAccountId") merchantAccountId: String,
        @Param("merchantOrderId") merchantOrderId: String,
    ): List<JpaIncidentHoldEntity>
}

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

interface OutboxEventJpaRepository : JpaRepository<JpaOutboxEventEntity, Long>
