package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.domain.AcquisitionOpsDecision

/**
 * ADMIN 조작 결정 기록 Port(ACQ-ADMIN-001 — CONVENTIONS §14 Store ③ 프로토콜 기록: 승인 ID별 최초 확정 결정, append-only). 호출자 TX 필수.
 * 같은 승인 ID 두 번째 [append]는 UNIQUE 위반([AcquiringPersistenceFailure.UniqueConflict]) — **잡아서 같은 TX를 계속하지 않는다**(TX 전체 rollback 뒤 새 TX가
 * 커밋된 결정을 재생한다 — 결정 없는 업무 효과가 남지 않게).
 */
interface AcquisitionOpsDecisionStore {
    fun find(approvalId: String): AcquisitionOpsDecision?

    fun append(decision: AcquisitionOpsDecision)
}
