package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** CANCEL-001 S2 Spring Data repository(관례 §14). [JpaReturnReservationEntity.seq]가 domain 예약 순서다. */
interface ReturnReservationJpaRepository : JpaRepository<JpaReturnReservationEntity, String> {
    @Query("select r from JpaReturnReservationEntity r where r.paymentId = :paymentId order by r.seq")
    fun findByPayment(@Param("paymentId") paymentId: String): List<JpaReturnReservationEntity>
}
