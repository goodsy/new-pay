package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.ContractIntakeTransition

/**
 * 접수 건 전이 이력 저장 Port(MERCHANT-FLOW-001 §4A.3 — 결정 로그라 Aggregate가 아닌 `…Store`, ARCHITECTURE-CONVENTIONS §14 기준 ①). **INSERT 전용**(§4A.14 M-2):
 * 고치거나 지우는 메서드가 없다. 사업자 행 잠금 뒤 같은 TX에서 부른다.
 */
interface ContractIntakeTransitionStore {
    /** 그 접수 건 안 다음 순번(1, 2, …)으로 한 행 INSERT하고 부여한 순번을 돌려준다. PK(사업자, 접수 건, 순번)가 이중 방어 — 위반은 결함. */
    fun append(transition: ContractIntakeTransition): Int
}
