package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** AUTHN-001 Spring Data repository — 잠금 규칙은 [CheckoutTokenJpaRepository] 설명과 같다. */
interface AuthenticationSessionEventJpaRepository : JpaRepository<JpaAuthenticationSessionEventEntity, AuthenticationSessionEventKey> {
    @Query("select e from JpaAuthenticationSessionEventEntity e where e.key.authenticationSessionId = :sessionId order by e.key.seq")
    fun findBySessionOrdered(@Param("sessionId") sessionId: String): List<JpaAuthenticationSessionEventEntity>

    @Query("select max(e.key.seq) from JpaAuthenticationSessionEventEntity e where e.key.authenticationSessionId = :sessionId")
    fun maxSeq(@Param("sessionId") sessionId: String): Int?

    @Query(
        "select count(e) > 0 from JpaAuthenticationSessionEventEntity e where e.key.authenticationSessionId = :sessionId and e.kind = :kind " +
            "and ((:detail is null and e.detailCode is null) or e.detailCode = :detail)",
    )
    fun existsRecorded(@Param("sessionId") sessionId: String, @Param("kind") kind: String, @Param("detail") detail: String?): Boolean
}
