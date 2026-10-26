package com.paycore.admin.approval.domain

/** 승인·거절·철회 명령의 판정(§4.7.2 판정표). [Refused]는 상태 변경 없음, [Expired]는 만료로 바꾼 새 값(커밋 대상). */
sealed interface ApprovalDecision {
    data object Proceed : ApprovalDecision

    data object AlreadyDecided : ApprovalDecision

    data class Expired(val expired: ApprovalRequest) : ApprovalDecision

    data class Refused(val code: String) : ApprovalDecision
}
