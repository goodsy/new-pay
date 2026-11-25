package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** 접수 건 행 읽기(Spring Data — 쓰기는 [JpaContractIntakeRepository]가 `EntityManager.persist`·변경 감지로 한다). 최신 = 생성 순번이 가장 큰 행(리뷰 m-3). */
interface ContractIntakeJpaRepository : JpaRepository<JpaContractIntakeEntity, JpaContractIntakeEntity.Key> {
    fun findFirstByBusinessIdOrderByIntakeSeqDesc(businessId: String): JpaContractIntakeEntity?
}
