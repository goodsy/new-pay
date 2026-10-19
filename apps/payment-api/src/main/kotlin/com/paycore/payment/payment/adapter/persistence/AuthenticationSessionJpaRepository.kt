package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** AUTHN-001 Spring Data repository — 잠금 규칙은 [CheckoutTokenJpaRepository] 설명과 같다. */
interface AuthenticationSessionJpaRepository : JpaRepository<JpaAuthenticationSessionEntity, String> {
    @Query(
        value = "SELECT authentication_session_id FROM authentication_session WHERE payment_id = :paymentId ORDER BY created_at, authentication_session_id FOR UPDATE",
        nativeQuery = true,
    )
    fun lockIdsByPayment(@Param("paymentId") paymentId: String): List<String>

    @Query(
        "select a.authenticationSessionId from JpaAuthenticationSessionEntity a " +
            "where a.checkoutSessionId = :checkoutSessionId and a.stateDigest = :stateDigest",
    )
    fun findIdByCheckoutSessionAndState(@Param("checkoutSessionId") checkoutSessionId: String, @Param("stateDigest") stateDigest: String): String?

    @Query("select a.authenticationSessionId from JpaAuthenticationSessionEntity a where a.authenticationRef = :authenticationRef")
    fun findIdByAuthenticationRef(@Param("authenticationRef") authenticationRef: String): String?
}
