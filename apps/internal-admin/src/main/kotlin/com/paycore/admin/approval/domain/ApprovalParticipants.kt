package com.paycore.admin.approval.domain

/**
 * 2인 규칙(D-ADMIN1-004·009, §4.5.1·§4.7.7 H-A) — 승인 요청에 참여하는 계정의 **현재값**(잠근 행)으로 판정한다.
 * - 요청자: ACTIVE + 종류의 요청자 역할([requesterRole] — ADMIN 소유 6종 SECURITY_ADMIN([REQUESTER_ROLE]), `OPERATOR_CORRECTION_CANCEL` OPERATOR). 승인자: ACTIVE + APPROVER.
 * - 계정 대상은 요청자·승인자·대상이 모두 달라야 하고, Payment 대상은 요청자 ≠ 승인자([distinct] — ADMIN-PAYMENT-OPS-001 §4.9).
 * - `ROLE_REACTIVATE`: 요청자·승인자 모두 원인 재설정의 **요청자와도, 승인자(코드 수령자)와도** 달라야 한다(H-A — Coordinator fail-closed 판정).
 * - 권한 상승(`ROLE_GRANT`·`ROLE_REACTIVATE`): 요청자·승인자는 대상의 **현재 자격 발급 근거**의 요청자·승인자(코드 수령자, 1인 생성·재발급 수행자 포함)가
 *   아니어야 한다(D-ADMIN1-014·015 — 다음 재설정 전까지, 코드 사용 여부와 무관).
 */
object ApprovalParticipants {
    const val REQUESTER_ROLE = "SECURITY_ADMIN"
    const val APPROVER_ROLE = "APPROVER"
    const val CAUSE_PARTICIPANT = "CAUSE_PARTICIPANT"

    /** 원인 재설정 참여자(요청자·승인자)와 겹치면 거절 code, 아니면 null. */
    fun reactivationConflict(actor: String, causeRequester: String, causeApprover: String?): String? =
        if (actor == causeRequester || actor == causeApprover) CAUSE_PARTICIPANT else null

    const val CREDENTIAL_ISSUER = "CREDENTIAL_ISSUER"

    /** 대상 자격 발급 근거의 참여자면 거절 code, 아니면 null. */
    fun credentialIssuerConflict(kind: ApprovalKind, actor: String, targetCredentialIssuers: Set<String>): String? =
        if (kind.escalatesPrivilege && actor in targetCredentialIssuers) CREDENTIAL_ISSUER else null

    fun distinct(requester: String, approver: String, target: String): Boolean = setOf(requester, approver, target).size == 3

    /** 요청자 역할 = 종류의 요청자 역할(ADMIN-PAYMENT-OPS-001 §4.9 — ADMIN 소유 SECURITY_ADMIN, 운영자 보정 취소 OPERATOR). */
    fun requesterRole(kind: ApprovalKind): String = kind.requesterRole

    /** 대상별 2인 규칙: 계정 대상은 요청자·승인자·대상 모두 다름, Payment 대상은 요청자 ≠ 승인자(대상이 사람이 아니다). */
    fun distinct(target: ApprovalTarget, requester: String, approver: String): Boolean = when (target) {
        is ApprovalTarget.AdminAccount -> distinct(requester, approver, target.actorId)
        is ApprovalTarget.Payment, is ApprovalTarget.AcquisitionFile -> requester != approver
    }
}
