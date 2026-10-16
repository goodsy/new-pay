package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

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
