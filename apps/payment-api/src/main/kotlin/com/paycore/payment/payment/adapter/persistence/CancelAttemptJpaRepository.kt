package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** CANCEL-001 S2 Spring Data repository(관례 §14). */
interface CancelAttemptJpaRepository : JpaRepository<JpaCancelAttemptEntity, String> {
    @Query("select a from JpaCancelAttemptEntity a where a.cancellationId = :cancellationId")
    fun findByCancellation(@Param("cancellationId") cancellationId: String): List<JpaCancelAttemptEntity>
}
