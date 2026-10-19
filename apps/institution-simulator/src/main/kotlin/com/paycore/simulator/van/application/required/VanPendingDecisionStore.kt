package com.paycore.simulator.van.application.required

import com.paycore.simulator.van.domain.VanNamespace
import com.paycore.simulator.van.domain.VanPendingDecision
import java.time.Instant

/**
 * PENDING 예약 기록 Port(AUTH-001 §5.5 `van_pending_decision`). 예약 INSERT(UNIQUE namespace — 충돌은 [DuplicateVanRecord])와 확정 반영(PENDING → RESOLVED, 한 번 —
 * 이미 확정된 예약이면 [DuplicateVanRecord])만 있다. TX 없이 호출하면 거부한다.
 */
interface VanPendingDecisionStore {
    fun find(namespace: VanNamespace): VanPendingDecision?

    fun insert(pending: VanPendingDecision)

    fun markResolved(pending: VanPendingDecision, at: Instant)
}
