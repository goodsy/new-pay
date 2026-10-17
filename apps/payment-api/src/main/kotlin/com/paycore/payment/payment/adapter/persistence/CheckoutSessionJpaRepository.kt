package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** AUTHN-001 Spring Data repository — 잠금 규칙은 [CheckoutTokenJpaRepository] 설명과 같다. */
interface CheckoutSessionJpaRepository : JpaRepository<JpaCheckoutSessionEntity, String> {
    fun findByHandle(handle: String): JpaCheckoutSessionEntity?

    @Query(
        value = "SELECT checkout_session_id FROM checkout_session WHERE payment_id = :paymentId AND status = 'ACTIVE' ORDER BY checkout_session_id FOR UPDATE",
        nativeQuery = true,
    )
    fun lockActiveIdsByPayment(@Param("paymentId") paymentId: String): List<String>
}
