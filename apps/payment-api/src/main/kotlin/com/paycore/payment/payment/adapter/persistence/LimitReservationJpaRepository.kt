package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/** AUTH-001 S2 Spring Data repository(관례 §14). 잠금 읽기는 `PessimisticRowLock.lockRow`를 쓰고 JPQL @Lock 조회는 쓰지 않는다(관례 §9). */
interface LimitReservationJpaRepository : JpaRepository<JpaLimitReservationEntity, String> {
    @Query(
        "select r from JpaLimitReservationEntity r where r.environment = :environment and r.merchantAccountId = :merchantAccountId " +
            "and r.currency = :currency and r.businessDate = :businessDate order by r.seq",
    )
    fun findByUsage(
        @Param("environment") environment: String,
        @Param("merchantAccountId") merchantAccountId: String,
        @Param("currency") currency: String,
        @Param("businessDate") businessDate: LocalDate,
    ): List<JpaLimitReservationEntity>
}
