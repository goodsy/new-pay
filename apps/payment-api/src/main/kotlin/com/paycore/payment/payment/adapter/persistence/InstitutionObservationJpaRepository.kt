package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** AUTH-001 S2 Spring Data repository(관례 §14). 잠금 읽기는 `PessimisticRowLock.lockRow`를 쓰고 JPQL @Lock 조회는 쓰지 않는다(관례 §9). */
interface InstitutionObservationJpaRepository : JpaRepository<JpaInstitutionObservationEntity, Long> {
    @Query("select o from JpaInstitutionObservationEntity o where o.authorizationId = :authorizationId order by o.id")
    fun findByAuthorization(@Param("authorizationId") authorizationId: String): List<JpaInstitutionObservationEntity>
}
