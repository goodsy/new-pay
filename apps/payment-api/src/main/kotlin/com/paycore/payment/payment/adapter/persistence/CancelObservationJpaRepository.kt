package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** CANCEL-001 S2 Spring Data repository(관례 §14). 저장 순서(id)가 domain 기록 순서다. */
interface CancelObservationJpaRepository : JpaRepository<JpaCancelObservationEntity, Long> {
    @Query("select o from JpaCancelObservationEntity o where o.cancellationId = :cancellationId order by o.id")
    fun findByCancellation(@Param("cancellationId") cancellationId: String): List<JpaCancelObservationEntity>
}
