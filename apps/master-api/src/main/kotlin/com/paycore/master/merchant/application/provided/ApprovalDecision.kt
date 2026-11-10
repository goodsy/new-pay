package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.MasterRejection

/** MASTER 근거 행 결정(§9.4 결정 조회). [Decided.reference]는 성공만, [Decided.rejection]은 거절만 값이 있다. */
sealed interface ApprovalDecision {
    data object None : ApprovalDecision

    data class Decided(
        val operation: AdminOperation,
        val outcome: ApprovalOutcome,
        val reference: String?,
        val rejection: MasterRejection?,
        val approvalFingerprint: String,
        val decidedVia: ApprovalDecidedVia,
    ) : ApprovalDecision {
        init {
            require((outcome == ApprovalOutcome.EXECUTED) == (reference != null && rejection == null)) { "an executed decision has only a reference" }
            require((outcome == ApprovalOutcome.REJECTED) == (rejection != null && reference == null)) { "a rejected decision has only a rejection" }
        }
    }
}

/** 근거 행 결과(영속 값 `admin_approval_evidence.outcome`). */
enum class ApprovalOutcome { EXECUTED, REJECTED }

/** 근거 행을 만든 경로(영속 값 `admin_approval_evidence.decided_via` — C-29): 실행 요청 / 기한 확인 전용 호출. */
enum class ApprovalDecidedVia { EXECUTE, DEADLINE_CONFIRM }
