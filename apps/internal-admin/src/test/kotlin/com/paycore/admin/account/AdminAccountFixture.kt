package com.paycore.admin.account

import com.paycore.admin.account.domain.AccountChange
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.CredentialBasis
import com.paycore.admin.account.domain.DisplayName
import com.paycore.admin.account.domain.EnrollmentPurpose
import com.paycore.admin.account.domain.LoginName
import com.paycore.admin.account.domain.NewTotp
import com.paycore.admin.account.domain.PasswordHash
import com.paycore.admin.account.domain.PendingEnrollment
import com.paycore.admin.account.domain.RoleEventActor
import com.paycore.admin.account.domain.TotpCredential
import com.paycore.admin.approval.domain.ApprovalDecision
import com.paycore.admin.approval.domain.ApprovalKind
import com.paycore.admin.approval.domain.ApprovalRequest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 관리자 계정 Named Fixture(Test Fixture Policy — Domain Factory → Named Fixture). 모든 계정은 domain 공식 전이로 만든다(reconstitute로 상태를 직접
 * 조립하지 않는다 — Invalid Fixture는 [AdminAccountSpec]의 reconstitute 거부 시험에서만, 이름에 의도를 적는다):
 * - [pending] = 생성, [enrolledActive] = 생성 → ENROLL 등록 완료.
 * - [bootstrapped] = 생성 → 부트스트랩 주체의 역할 부여 → 등록 완료. **부트스트랩이 실제로 만드는 상태만**: SECURITY_ADMIN 또는 APPROVER **단일** 역할(§4.9.1).
 * - [withRolesViaApproval] = 그 밖의 역할·겸직(S5 — 우회 준비 교체): 생성 → ENROLL 등록 완료 → 역할마다 **2인 승인 domain 경로**(approval `ApprovalRequest.create` →
 *   `decideApproval`(요청자 ≠ 승인자 ≠ 대상) → `executed`) 뒤 그 approvalId로 `grantRole`(행위자 = 승인자). DB를 쓰는 시험은 실제 Use Case 경로(`ApprovalPath`)를 쓴다.
 */
object AdminAccountFixture {
    val NOW: Instant = Instant.parse("2026-10-25T01:02:03.456Z")
    val PASSWORD_HASH = PasswordHash("{bcrypt}\$2a\$12\$" + "a".repeat(53))
    val OTHER_PASSWORD_HASH = PasswordHash("{bcrypt}\$2a\$12\$" + "b".repeat(53))
    val TOTP = TotpCredential(ByteArray(48) { it.toByte() }, keyVersion = 1)
    val OTHER_TOTP = TotpCredential(ByteArray(48) { (it + 7).toByte() }, keyVersion = 1)

    private val sequence = AtomicLong(1)

    /** 결정적 시험 actor ID(`adm_` + 26자, 순번 — 운영 ID 생성기와 무관). */
    fun newActorId(): AdminActorId = AdminActorId("adm_" + sequence.getAndIncrement().toString().padStart(26, '0'))

    fun enrollBasisOf(id: AdminActorId): String = "enc_" + id.value.removePrefix("adm_")

    /**
     * 1인 생성 경로(생성자 [creator] = 코드 수령자 — D-ADMIN1-015, 기본은 실제 계정이 아닌 [FIXTURE_CREATOR]). [creator]가 null이면 시스템 경로
     * (부트스트랩 — 배제 대상 없음).
     */
    fun pending(id: AdminActorId = newActorId(), login: String = "login-${id.value.takeLast(8).lowercase()}", creator: AdminActorId? = FIXTURE_CREATOR): AdminAccount =
        AdminAccount.create(
            id, LoginName(login), DisplayName("Admin ${id.value.takeLast(4)}"),
            creator?.let { CredentialBasis.oneShotCommand(enrollBasisOf(id), it) } ?: CredentialBasis.system(enrollBasisOf(id)), NOW,
        )

    fun enrolledActive(id: AdminActorId = newActorId()): AdminAccount = enroll(pending(id))

    /** 부트스트랩 경로(§4.9.1): SECURITY_ADMIN 또는 APPROVER 하나만. */
    fun bootstrapped(role: AdminRole, id: AdminActorId = newActorId()): AdminAccount {
        require(role in BOOTSTRAP_ROLES) { "bootstrap creates only SECURITY_ADMIN or APPROVER accounts" }
        return enroll(applied(pending(id, creator = null).grantRole(role, RoleEventActor.SystemBootstrap, approvalId = null, now = NOW)))
    }

    /** 2인 승인 domain 경로로 역할을 받은 ACTIVE 계정(요청자 [APPROVAL_REQUESTER] SECURITY_ADMIN, 승인자 [APPROVAL_APPROVER] APPROVER — 모두 대상과 다름). */
    fun withRolesViaApproval(vararg roles: AdminRole, id: AdminActorId = newActorId()): AdminAccount {
        require(roles.isNotEmpty()) { "at least one role" }
        var account = enrolledActive(id)
        for (role in roles) {
            val approval = ApprovalRequest.create(
                newApprovalId(), ApprovalKind.ROLE_GRANT, account.id.value, account.securityVersion, "{}", role.name, null,
                APPROVAL_REQUESTER.value, "Requester", NOW, Duration.ofHours(24),
            )
            check(approval.decideApproval(APPROVAL_APPROVER.value, NOW) == ApprovalDecision.Proceed) { "fixture approval must be decidable" }
            val executed = approval.executed(APPROVAL_APPROVER.value, "Approver", NOW)
            account = applied(account.grantRole(role, RoleEventActor.Admin(APPROVAL_APPROVER), executed.id, NOW))
        }
        return account
    }

    private val approvalSequence = AtomicLong(1)

    fun newApprovalId(): String = "apr_" + approvalSequence.getAndIncrement().toString().padStart(26, '0')

    val APPROVAL_REQUESTER = AdminActorId("adm_" + "Y".repeat(26))
    val APPROVAL_APPROVER = AdminActorId("adm_" + "Z".repeat(26))

    /** 1인 생성 Fixture의 생성자 자리(실제 계정 아님 — 어느 시험의 참여자와도 겹치지 않는다). */
    val FIXTURE_CREATOR = AdminActorId("adm_" + "X".repeat(26))

    val BOOTSTRAP_ROLES = setOf(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)

    fun enroll(account: AdminAccount): AdminAccount = applied(
        account.completeEnrollment(
            PendingEnrollment(EnrollmentPurpose.ENROLL, requireNotNull(account.pending).basis),
            PASSWORD_HASH,
            NewTotp(TOTP, confirmedStep = 1000),
            NOW,
        ),
    )

    fun applied(change: AccountChange): AdminAccount = when (change) {
        is AccountChange.Applied -> change.account
        is AccountChange.Rejected -> error("fixture transition rejected: ${change.reason}")
    }
}
