package com.paycore.simulator.authentication.adapter.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** Simulator 거래 Spring Data repository(adapter.persistence 전용). */
interface AuthenticationTransactionJpaRepository : JpaRepository<JpaAuthenticationTransactionEntity, String> {
    fun findByClientIdAndState(clientId: String, state: String): JpaAuthenticationTransactionEntity?

    fun findByResultRef(resultRef: String): JpaAuthenticationTransactionEntity?

    fun findAllByEvidenceRef(evidenceRef: String): List<JpaAuthenticationTransactionEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from JpaAuthenticationTransactionEntity t where t.transactionId = :id")
    fun lockById(@Param("id") id: String): JpaAuthenticationTransactionEntity?
}
