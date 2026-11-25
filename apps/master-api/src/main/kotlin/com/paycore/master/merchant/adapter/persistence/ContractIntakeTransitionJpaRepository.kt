package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** 전이 이력 읽기(INSERT는 [JpaContractIntakeTransitionStore]가 persist로만). */
interface ContractIntakeTransitionJpaRepository : JpaRepository<JpaContractIntakeTransitionEntity, JpaContractIntakeTransitionEntity.Key> {
    @Query(
        "select coalesce(max(t.transitionSeq), 0) from JpaContractIntakeTransitionEntity t where t.businessId = :businessId and t.intakeSeq = :intakeSeq",
    )
    fun lastSeq(@Param("businessId") businessId: String, @Param("intakeSeq") intakeSeq: Int): Int
}
