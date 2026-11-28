package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractIntake

/**
 * 계약 접수 건 Aggregate 저장 Port(MERCHANT-FLOW-001 §4A.15 — 구현 `JpaContractIntakeRepository`). 모든 메서드는 **사업자 행 잠금 뒤 같은 MASTER 로컬 TX**에서 부른다
 * (`BusinessRepository.lock` — `PESSIMISTIC_WRITE`, 사업자 version 증가 없음 — 리뷰 m-4). 잠금 순서는 항상 사업자 → 접수 건(교착 방지).
 * 화면 조회는 이 Port가 아니라 jOOQ `ContractIntakeReader`다(CQRS).
 */
interface ContractIntakeRepository {
    /** 그 사업자의 최신 접수 건(생성 순번이 가장 큰 건 — 리뷰 m-3), 없으면 null. 새 접수 생성 조건의 입력으로만 쓴다(관문 입력 아님 — BR-BIZ-319, ELIG F-4). */
    fun latest(businessId: BusinessId): ContractIntake?

    /** 그 사업자의 접수 건 [seq](명령 대상 — FLOW-2b), 없으면 null. 잠금 뒤 DB를 직접 읽는다(영속성 컨텍스트의 관리 Entity를 덮어 읽음). */
    fun find(businessId: BusinessId, seq: Int): ContractIntake?

    /** 새 접수 건 persist(version 0, 종류·대상 포함 — INSERT 때만 쓴다). 열린 건 중복은 DB `uk_cin_open`이 이중 방어 — 위반은 결함([StoreConstraint.CONTRACT_INTAKE_ROW]). */
    fun insert(intake: ContractIntake)

    /** 전이 결과 저장([intake].version = 저장 후 기대 version, 정확히 +1). 저장된 version이 [intake].version - 1이 아니면 [PersistenceFailure.OptimisticConflict]. */
    fun update(intake: ContractIntake)

    /**
     * FLOW-2d 접수 항목 수정 결과 저장(접수일·경로·담당자·희망 서비스 + 갱신 시각·행위자, version 정확히 +1). 상태·종류·대상·이관 표시는 바뀌지 않는다(adapter가 확인).
     * 저장된 version이 [intake].version - 1이 아니면 [PersistenceFailure.OptimisticConflict].
     */
    fun updateItems(intake: ContractIntake)
}
