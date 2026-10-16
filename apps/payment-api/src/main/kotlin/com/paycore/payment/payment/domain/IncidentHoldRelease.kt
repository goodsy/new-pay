package com.paycore.payment.payment.domain

import java.time.Instant

/** 재대사가 확인한 사건의 실제(해제 입력). 해제 뒤 주문 효과를 정한다(명세 §8.2 도출). */
enum class HoldReleaseFinding {
    /** 늦은/상충 승인이 실제 기관 승인이었다 → 승인 성공 이력이 있는 주문(취소 여부와 무관하게 새 Payment 금지, 시도 §2). */
    REAL_APPROVAL_CONFIRMED,

    /** 기관 무효과가 확인됐다 → 원 종료 근거 유지, D-CPD-002대로 판단. */
    NO_EFFECT_CONFIRMED,

    /**
     * 실제 이중 승인 확인 + 보정(취소) 완료(D-AUTH-011): 이 hold의 승인(이전 generation 늦은 승인)과 주문의 다른 승인 성공(현재 generation)이
     * 모두 실제였고 보정을 마쳤다. 주문 승인 이력은 기존(현재 generation) 그대로, 새 Payment는 계속 금지. 보정 참조가 필수다.
     */
    DOUBLE_APPROVAL_CORRECTED,
}

/**
 * 사건 보류 해제 기록(append-only, D-AUTH-002 — 사용자 확정 2026-10-15). 요청자 ≠ 승인자(2인 승인), 재대사 MATCH 증거 참조와 사유가
 * 필수다. [HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED]이면 보정(취소) 참조 [correctionRef]도 필수이고, 그 밖에는 두지 않는다(D-AUTH-011).
 * 역할·Scope·승인한 입력 버전 = 실행 입력 검증과 보정 실행은 운영 도구 Use Case(Q-ADMIN-AUTH, RECON-CONTRACT-001)의 책임이다.
 */
data class IncidentHoldRelease(
    val hold: IncidentHold,
    val requestedBy: OperatorId,
    val approvedBy: OperatorId,
    val reconciliationMatchRef: String,
    val finding: HoldReleaseFinding,
    val reason: String,
    val releasedAt: Instant,
    val correctionRef: String? = null,
) {
    init {
        require(requestedBy != approvedBy) { "hold release needs two different people (requester must not approve)" }
        require(reconciliationMatchRef.isNotBlank()) { "hold release needs a re-reconciliation MATCH reference" }
        require(reason.isNotBlank()) { "hold release needs a reason" }
        if (finding == HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED) {
            require(!correctionRef.isNullOrBlank()) { "a double-approval release needs a correction (cancellation) reference" }
        } else {
            require(correctionRef == null) { "a correction reference belongs only to a double-approval release" }
        }
    }
}
