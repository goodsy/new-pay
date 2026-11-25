package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractIntake

/**
 * 계약 접수 건 Aggregate 저장 Port(MERCHANT-FLOW-001 §4A.15 — 구현 `JpaContractIntakeRepository`). 모든 메서드는 **사업자 행 잠금 뒤 같은 MASTER 로컬 TX**에서 부른다
 * (`BusinessRepository.lock` — `PESSIMISTIC_WRITE`, 사업자 version 증가 없음 — 리뷰 m-4). 잠금 순서는 항상 사업자 → 접수 건(교착 방지).
 * 화면 조회는 이 Port가 아니라 jOOQ `ContractIntakeReader`다(CQRS).
 */
interface ContractIntakeRepository {
    /** 그 사업자의 최신 접수 건(생성 순번이 가장 큰 건 — 리뷰 m-3), 없으면 null. 생성 조건·관문 판정의 입력이다. */
    fun latest(businessId: BusinessId): ContractIntake?

    /** 새 접수 건 persist(version 0). 열린·완료 건 중복은 DB `uk_cin_active`가 이중 방어 — 위반은 결함([StoreConstraint.CONTRACT_INTAKE_ROW]). */
    fun insert(intake: ContractIntake)

    /** 전이 결과 저장([intake].version = 저장 후 기대 version, 정확히 +1). 저장된 version이 [intake].version - 1이 아니면 [PersistenceFailure.OptimisticConflict]. */
    fun update(intake: ContractIntake)
}
