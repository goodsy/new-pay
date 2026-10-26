package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.RequestApproval
import com.paycore.admin.support.TestTotp
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 실제 2인 승인 경로 Named Fixture(S5 — `withRoleViaApproval`, 우회 준비 교체). 요청자(부트스트랩 SECURITY_ADMIN)와 승인자(부트스트랩 APPROVER, 실제 TOTP)를 매번 새로 만들어
 * `ApprovalUseCase.request` → `approve`를 그대로 부른다(같은 승인자의 TOTP 단계 재사용을 피한다). 결과가 실행이 아니면 시험 준비 실패로 멈춘다.
 */
@Component
class ApprovalPath(private val approvals: ApprovalUseCase, private val ready: LoginReadyAccounts, private val storage: AdminAccountStorage) {

    class Participants(val requester: AdminAccount, val approver: LoginReadyAccounts.LoginReady)

    fun participants(): Participants = Participants(storage.securityAdmin(), ready.bootstrapped(AdminRole.APPROVER))

    fun context() = AdminRequestContext("corr-" + UUID.randomUUID().toString().take(8), "10.0.0.9")

    /** 요청만(승인 전) — 승인 ID. */
    fun request(kind: String, target: AdminActorId, grantedRole: String? = null, cause: String? = null, requester: AdminAccount = storage.securityAdmin()): String {
        val outcome = approvals.request(
            RequestApproval(requester.id.value, storage.load(requester.id).sessionGeneration, kind, target.value, grantedRole, cause, context()),
        )
        return (outcome as? ApprovalOutcome.Created ?: error("fixture approval request failed: $outcome")).approvalId
    }

    /** 승인(새 승인자 또는 주어진 승인자, 현재 TOTP). */
    fun approve(approvalId: String, approver: LoginReadyAccounts.LoginReady = ready.bootstrapped(AdminRole.APPROVER)): ApprovalOutcome =
        approvals.approve(DecideApproval(approvalId, approver.account.id.value, storage.load(approver.account.id).sessionGeneration, TestTotp.code(approver.secret), context()))

    fun execute(kind: String, target: AdminActorId, grantedRole: String? = null, cause: String? = null): ApprovalOutcome.Executed =
        approve(request(kind, target, grantedRole, cause)) as? ApprovalOutcome.Executed ?: error("fixture approval was not executed")

    fun grant(target: AdminActorId, vararg roles: AdminRole): AdminAccount {
        roles.forEach { execute("ROLE_GRANT", target, it.name) }
        return storage.load(target)
    }

    /** 2인 승인 경로로 역할을 받은 ACTIVE 계정(저장됨). */
    fun withRolesViaApproval(vararg roles: AdminRole): AdminAccount = grant(storage.save(AdminAccountFixture.enrolledActive()).id, *roles)

    /** `PASSWORD_RESET`·`TOTP_REENROLL` 실행 — 대기 계정과 승인 응답에만 나온 코드 원문, 승인 ID. */
    fun credentialReset(kind: String, target: AdminActorId): Triple<AdminAccount, String, String> {
        val executed = execute(kind, target)
        return Triple(storage.load(target), requireNotNull(executed.enrollmentCode), executed.approvalId)
    }
}
