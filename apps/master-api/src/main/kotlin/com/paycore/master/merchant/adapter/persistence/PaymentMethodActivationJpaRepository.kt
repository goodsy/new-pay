package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PaymentMethodActivationJpaRepository : JpaRepository<JpaPaymentMethodActivationEntity, String> {
    @Query(
        "select a from JpaPaymentMethodActivationEntity a where a.merchantAccountId = :accountId and a.paymentMethod = :method " +
            "and a.status <> 'TERMINATED'",
    )
    fun findOpen(@Param("accountId") accountId: String, @Param("method") method: String): JpaPaymentMethodActivationEntity?

    @Query("select a from JpaPaymentMethodActivationEntity a where a.merchantAccountId = :accountId order by a.registeredAt, a.activationId")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaPaymentMethodActivationEntity>
}
