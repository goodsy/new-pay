package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.AdminOwnedCommandExecutor
import com.paycore.admin.account.application.provided.CreateAdminAccount
import com.paycore.admin.account.application.provided.EnrollmentResult
import com.paycore.admin.account.application.provided.EnrollmentUseCase
import com.paycore.admin.account.application.provided.OwnedCommandEvidence
import com.paycore.admin.account.application.provided.OwnedCommandOutcome
import com.paycore.admin.account.application.provided.RevokeAdminRole
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.RequestApproval
import com.paycore.admin.approval.application.provided.WithdrawApproval
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.UUID

/**
 * 자격 발급 근거 배제(D-ADMIN1-013·014·015 — S5 리뷰 High-1·Q-S5-1~3, Coordinator 반영 지시 2026-10-26):
 * - 013: 재설정 실행은 이미 정지된 역할에도 원인 = 새 재설정 SUSPEND를 남긴다 → `ROLE_REACTIVATE` 원인·배제는 항상 **최신** 재설정.
 * - 014: 계정은 현재 자격을 만든 코드 발급 근거(승인 ID 또는 1인 생성·재발급 명령)를 보존하고, 다음 재설정 전까지 그 근거의 요청자·승인자(코드 수령자)는
 *   그 계정의 권한 상승(`ROLE_GRANT`·`ROLE_REACTIVATE`) 요청자·승인자가 될 수 없다 — 코드 사용 전후, 정지 역할 회수 뒤 재부여에도.
 * - 015: 1인 생성으로 코드를 받은 SECURITY_ADMIN도 그 계정 자격이 바뀔 때까지 같은 배제.
 * 요청 시점(409 `CREDENTIAL_ISSUER` — 별도 TX 감사)과 승인 시점(403, TOTP 전 — 상태 불변) 모두, 소유 재검사(account)도 같은 규칙.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class CredentialIssuerExclusionSpec(
    private val approvals: ApprovalUseCase,
    private val path: ApprovalPath,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val commands: AdminAccountCommandUseCase,
    private val enrollment: EnrollmentUseCase,
    private val executor: AdminOwnedCommandExecutor,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun gen(id: AdminActorId) = storage.load(id).sessionGeneration
    /** SECURITY_ADMIN(부트스트랩) + APPROVER(2인 승인 경로) — 요청과 승인 둘 다 할 수 있는 관리자. */
    fun dual(): LoginReadyAccounts.LoginReady = ready.bootstrapped(AdminRole.SECURITY_ADMIN).also { path.grant(it.account.id, AdminRole.APPROVER) }
    fun request(requester: AdminActorId, kind: String, target: AdminActorId, role: String? = null, cause: String? = null): ApprovalOutcome =
        approvals.request(RequestApproval(requester.value, gen(requester), kind, target.value, role, cause, path.context()))
    fun created(outcome: ApprovalOutcome): String = outcome.shouldBeInstanceOf<ApprovalOutcome.Created>().approvalId
    /** [stepOffset]초 뒤 단계 코드(같은 승인자가 한 단계 안에서 두 번 승인하지 않게 — 창 ±1). */
    fun approve(id: String, approver: LoginReadyAccounts.LoginReady, stepOffset: Long = 0): ApprovalOutcome = approvals.approve(
        DecideApproval(id, approver.account.id.value, gen(approver.account.id), TestTotp.code(approver.secret, Instant.now().plusSeconds(stepOffset)), path.context()),
    )
    fun statusOf(id: String): String = AdminDbAccounts.root(container).use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) as String }
    /** 재설정 실행(요청 [requester]·승인 [approver]) — 코드 원문과 승인 ID. */
    fun reset(kind: String, target: AdminActorId, requester: LoginReadyAccounts.LoginReady, approver: LoginReadyAccounts.LoginReady): Pair<String, String> {
        val id = created(request(requester.account.id, kind, target))
        val executed = approve(id, approver).shouldBeInstanceOf<ApprovalOutcome.Executed>()
        return requireNotNull(executed.enrollmentCode) to id
    }
    /** 코드로 등록 완료(목적이 요구하는 요소만 — 비밀번호, 필요하면 TOTP). */
    fun enroll(target: AdminActorId, code: String) {
        var draft = enrollment.begin(code).shouldBeInstanceOf<EnrollmentResult.InProgress>().draft
        if (draft.purpose != "TOTP_REENROLL") {
            draft = enrollment.providePassword(draft, "issuer-spec-password-1".toCharArray()).shouldBeInstanceOf<EnrollmentResult.InProgress>().draft
        }
        val totpCode = if (draft.purpose != "PASSWORD_RESET") {
            val provisioning = enrollment.startTotp(draft).shouldBeInstanceOf<EnrollmentResult.TotpProvisioning>()
            draft = provisioning.draft
            TestTotp.code(TestTotp.secretOf(provisioning.otpauthUri))
        } else {
            null
        }
        enrollment.complete(draft, totpCode, path.context()) shouldBe EnrollmentResult.Completed(target.value)
    }

    Given("consecutive credential resets (D-ADMIN1-013)") {
        Then("the second reset re-suspends already suspended roles: reactivation cites only the latest reset, whose requester and approver are excluded; the first reset's participants are no longer excluded") {
            val target = path.withRolesViaApproval(AdminRole.OPERATOR)
            val (r1, p1) = dual() to dual()
            val (r2, p2) = dual() to dual()
            val (_, reset1) = reset("PASSWORD_RESET", target.id, r1, p1)
            val (code2, reset2) = reset("PASSWORD_RESET", target.id, r2, p2)
            enroll(target.id, code2)
            request(storage.securityAdmin().id, "ROLE_REACTIVATE", target.id, cause = reset1) shouldBe ApprovalOutcome.Rejected("NOTHING_TO_REACTIVATE")
            // 재리뷰 m-1: 최신 재설정 참여자가 **이전** 원인을 인용하면 H-A(원인 참여자) 판정은 통과하지만 자격 근거 판정이 먼저 막는다(근거 판정 없으면 NOTHING_TO_REACTIVATE).
            request(r2.account.id, "ROLE_REACTIVATE", target.id, cause = reset1) shouldBe ApprovalOutcome.Rejected("CREDENTIAL_ISSUER")
            listOf(request(r2.account.id, "ROLE_REACTIVATE", target.id, cause = reset2), request(p2.account.id, "ROLE_REACTIVATE", target.id, cause = reset2)) shouldBe
                List(2) { ApprovalOutcome.Rejected("CAUSE_PARTICIPANT") }
            val reactivation = created(request(storage.securityAdmin().id, "ROLE_REACTIVATE", target.id, cause = reset2))
            approve(reactivation, p2, stepOffset = 30) shouldBe ApprovalOutcome.Refused("CAUSE_PARTICIPANT")
            statusOf(reactivation) shouldBe "PENDING"
            approve(reactivation, p1, stepOffset = 30).shouldBeInstanceOf<ApprovalOutcome.Executed>()
            storage.load(target.id).activeRoles shouldBe setOf(AdminRole.OPERATOR)
        }
    }

    Given("the credential basis of an account (D-ADMIN1-014)") {
        Then("the reset's requester and approver cannot request or approve ROLE_GRANT on it — before the code is used, after re-enrollment, and after the suspended role was revoked (review High-1)") {
            val target = path.withRolesViaApproval(AdminRole.OPERATOR)
            val (r, p) = dual() to dual()
            val (code, _) = reset("PASSWORD_RESET", target.id, r, p)
            // 코드 사용 전.
            val before = created(request(storage.securityAdmin().id, "ROLE_GRANT", target.id, "VIEWER"))
            approve(before, p, stepOffset = 30) shouldBe ApprovalOutcome.Refused("CREDENTIAL_ISSUER")
            statusOf(before) shouldBe "PENDING"
            approve(before, ready.bootstrapped(AdminRole.APPROVER)).shouldBeInstanceOf<ApprovalOutcome.Executed>()
            // 코드 사용 뒤 + 정지 역할 회수 뒤 같은 역할 재부여.
            enroll(target.id, code)
            commands.revokeRole(RevokeAdminRole(storage.securityAdmin().id.value, target.id.value, "OPERATOR", path.context())) shouldBe AccountCommandResult.Succeeded(target.id.value)
            request(r.account.id, "ROLE_GRANT", target.id, "OPERATOR") shouldBe ApprovalOutcome.Rejected("CREDENTIAL_ISSUER")
            request(p.account.id, "ROLE_GRANT", target.id, "OPERATOR") shouldBe ApprovalOutcome.Rejected("CREDENTIAL_ISSUER")
            val regrant = created(request(storage.securityAdmin().id, "ROLE_GRANT", target.id, "OPERATOR"))
            approve(regrant, p, stepOffset = 30) shouldBe ApprovalOutcome.Refused("CREDENTIAL_ISSUER")
            approve(regrant, r, stepOffset = 30) shouldBe ApprovalOutcome.Refused("CREDENTIAL_ISSUER")
            statusOf(regrant) shouldBe "PENDING"
            approve(regrant, ready.bootstrapped(AdminRole.APPROVER)).shouldBeInstanceOf<ApprovalOutcome.Executed>()
            storage.load(target.id).activeRoles shouldBe setOf(AdminRole.VIEWER, AdminRole.OPERATOR)
            AdminDbAccounts.root(container).use {
                (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE target_actor_id = ? AND detail LIKE '%reason=CREDENTIAL_ISSUER%'", target.id.value) as Number).toInt()
            } shouldBe 5
        }
        Then("the exclusion lifts after the next reset by others; the account owner re-check rejects issuer evidence too") {
            val target = path.withRolesViaApproval(AdminRole.VIEWER)
            val (r, p) = dual() to dual()
            reset("TOTP_REENROLL", target.id, r, p)
            val latest = storage.load(target.id)
            listOf(
                OwnedCommandEvidence("apr_" + "E".repeat(26), "ROLE_GRANT", storage.securityAdmin().id.value, p.account.id.value, target.id.value, latest.securityVersion, "OPERATOR", null),
                OwnedCommandEvidence("apr_" + "F".repeat(26), "ROLE_GRANT", r.account.id.value, storage.bootstrapped(AdminRole.APPROVER).id.value, target.id.value, latest.securityVersion, "OPERATOR", null),
            ).map { e -> storage.inTx { executor.execute(e) } } shouldBe List(2) { OwnedCommandOutcome.Rejected("CREDENTIAL_ISSUER") }
            reset("TOTP_REENROLL", target.id, dual(), dual())
            val grant = created(request(r.account.id, "ROLE_GRANT", target.id, "OPERATOR"))
            approve(grant, p, stepOffset = 30).shouldBeInstanceOf<ApprovalOutcome.Executed>()
        }
    }

    Given("an account created by a SECURITY_ADMIN with the 1-person command (D-ADMIN1-015)") {
        Then("its creator (the enrollment code recipient) cannot request or approve ROLE_GRANT on it, before and after enrollment, until its credential changes") {
            val creator = dual()
            val account = commands.createAccount(CreateAdminAccount(creator.account.id.value, "iss-" + UUID.randomUUID().toString().take(8), "피생성자", path.context()))
                .shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            val target = AdminActorId(account.targetActorId)
            request(creator.account.id, "ROLE_GRANT", target, "VIEWER") shouldBe ApprovalOutcome.Rejected("CREDENTIAL_ISSUER")
            enroll(target, requireNotNull(account.enrollmentCode))
            request(creator.account.id, "ROLE_GRANT", target, "VIEWER") shouldBe ApprovalOutcome.Rejected("CREDENTIAL_ISSUER")
            val grant = created(request(storage.securityAdmin().id, "ROLE_GRANT", target, "VIEWER"))
            approve(grant, creator) shouldBe ApprovalOutcome.Refused("CREDENTIAL_ISSUER")
            approve(grant, ready.bootstrapped(AdminRole.APPROVER)).shouldBeInstanceOf<ApprovalOutcome.Executed>()
            reset("PASSWORD_RESET", target, dual(), dual())
            created(request(creator.account.id, "ROLE_GRANT", target, "OPERATOR"))
        }
        Then("an ENROLL reissue does not change the credential: until enrollment both the previous creator and the reissue performer are excluded; on completion the previous creator is released (D-98, re-review m-2 ruling (b))") {
            val creator = dual()
            val reissuer = dual()
            val account = commands.createAccount(CreateAdminAccount(creator.account.id.value, "rei-" + UUID.randomUUID().toString().take(8), "재발급대상", path.context()))
                .shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            val target = AdminActorId(account.targetActorId)
            val reissued = commands.reissueEnrollCode(TargetedAccountCommand(reissuer.account.id.value, target.value, path.context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            listOf(request(creator.account.id, "ROLE_GRANT", target, "VIEWER"), request(reissuer.account.id, "ROLE_GRANT", target, "VIEWER")) shouldBe
                List(2) { ApprovalOutcome.Rejected("CREDENTIAL_ISSUER") }
            val other = storage.securityAdmin()
            val before = created(request(other.id, "ROLE_GRANT", target, "VIEWER"))
            listOf(approve(before, creator), approve(before, reissuer)) shouldBe List(2) { ApprovalOutcome.Refused("CREDENTIAL_ISSUER") }
            statusOf(before) shouldBe "PENDING"
            approvals.withdraw(WithdrawApproval(before, other.id.value, gen(other.id), path.context())) shouldBe ApprovalOutcome.Closed("WITHDRAWN")
            enroll(target, requireNotNull(reissued.enrollmentCode))
            // 등록 완료: 실제로 쓴 코드의 발급자(재발급 수행자)만 남고 이전 생성자는 풀린다.
            request(reissuer.account.id, "ROLE_GRANT", target, "VIEWER") shouldBe ApprovalOutcome.Rejected("CREDENTIAL_ISSUER")
            val after = created(request(creator.account.id, "ROLE_GRANT", target, "VIEWER"))
            approve(after, reissuer) shouldBe ApprovalOutcome.Refused("CREDENTIAL_ISSUER")
            approve(after, ready.bootstrapped(AdminRole.APPROVER)).shouldBeInstanceOf<ApprovalOutcome.Executed>()
            val grant = created(request(storage.securityAdmin().id, "ROLE_GRANT", target, "OPERATOR"))
            approve(grant, creator, stepOffset = 30).shouldBeInstanceOf<ApprovalOutcome.Executed>()
            storage.load(target).activeRoles shouldBe setOf(AdminRole.VIEWER, AdminRole.OPERATOR)
        }
    }
})
