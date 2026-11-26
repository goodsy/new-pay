package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.BusinessContract
import com.paycore.master.merchant.domain.BusinessContractChange
import com.paycore.master.merchant.domain.BusinessId

/**
 * 사업자 계약 Aggregate 저장 Port(MERCHANT-FLOW-001 §4B.2·§4B.12 — 구현 `JpaBusinessContractRepository`). 모든 메서드는 **사업자 행 잠금 뒤 같은 MASTER 로컬 TX**에서 부른다
 * (`BusinessRepository.lock` — `PESSIMISTIC_WRITE`, 사업자 version 증가 없음). 잠금 순서: 사업자 → 접수 건 → 사업자 계약(읽기 — 새 행은 INSERT만, §4B.9).
 * 화면 조회는 이 Port가 아니라 jOOQ Reader다(CQRS — 판정에 쓰지 않음).
 */
interface BusinessContractRepository {
    /**
     * 그 사업자의 최신 사업자 계약(계약 순번 최대 — 버전·범위·닫기·사건 포함), 없으면 null. 접수 종류 판정(BR-BIZ-325)·계약 완료·계약 명령·가맹점 등록 관문(FLOW-3)의 입력이다.
     * 잠금 뒤 DB를 직접 읽는다(영속성 컨텍스트에 있던 관리 Entity를 재사용하지 않는다 — §4B.8 MINOR-5).
     */
    fun latest(businessId: BusinessId): BusinessContract?

    /** [change]가 새로 만든 행만 INSERT(계약·버전·범위·닫기·사건 — UPDATE 없음). PK·UNIQUE 위반은 결함([StoreConstraint.BUSINESS_CONTRACT_ROW]). */
    fun insert(change: BusinessContractChange)
}
