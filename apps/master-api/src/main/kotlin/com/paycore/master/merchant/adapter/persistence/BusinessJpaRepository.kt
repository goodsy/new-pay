package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/*
 * Spring Data repository 공통(이 패키지의 *JpaRepository 전체, adapter.persistence 전용). application은 이것을 모르고 application.required Port 구현(Jpa*Repository·Jpa*Store)만 안다.
 * 잠금 읽기는 Store가 EntityManager.refresh(PESSIMISTIC_WRITE)로 한다(이미 영속성 컨텍스트에 있는 Entity도 최신 커밋 값으로 다시 읽기 위해).
 */

interface BusinessJpaRepository : JpaRepository<JpaBusinessEntity, String> {
    @Query("select b.businessId from JpaBusinessEntity b where b.registrationNumber = :registrationNumber")
    fun findIdByRegistrationNumber(@Param("registrationNumber") registrationNumber: String): String?
}
