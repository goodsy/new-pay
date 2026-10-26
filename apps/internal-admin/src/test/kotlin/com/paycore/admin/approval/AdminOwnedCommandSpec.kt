package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.AuthenticationFailureRecorder
import com.paycore.admin.account.application.provided.AuthenticationStep
import com.paycore.admin.account.application.provided.AdminOwnedCommandExecutor
import com.paycore.admin.account.application.provided.EnrollmentFailure
import com.paycore.admin.account.application.provided.EnrollmentResult
import com.paycore.admin.account.application.provided.EnrollmentUseCase
import com.paycore.admin.account.application.provided.OwnedCommandEvidence
import com.paycore.admin.account.application.provided.OwnedCommandOutcome
import io.kotest.assertions.throwables.shouldThrow
import org.springframework.transaction.IllegalTransactionStateException
import com.paycore.admin.account.application.provided.RevokeAdminRole
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import com.paycore.admin.account.application.required.AdminRoleEventStore
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.EnrollmentCodeHash
import com.paycore.admin.account.domain.EnrollmentPurpose
import com.paycore.admin.account.domain.RoleEventActor
import com.paycore.admin.account.domain.RoleEventType
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalQuery
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.RequestApproval
import com.paycore.admin.approval.application.provided.WithdrawApproval
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant

/**
 * ADMIN 소유 명령 6종의 단일 TX 실행(§4.7.3·§4.7.6·§4.7.7 — `AdminOwnedCommandSpec`·`CredentialResetSuspendsRolesSpec`·`ResetCodeDisclosureSpec`·`RoleReactivateSpec`·
 * `SelfApprovalForbiddenSpec`·`StepUpFailureCountsSpec`·`ApprovalExpirySpec`·`DuplicatePendingSpec`). 실제 MySQL·실제 TOTP(시험 쪽 독립 계산).
 * 모든 실행은 대상 security_version을 올린다 → 대상의 기존 세션은 다음 요청에서 끝난다(S4 세션 검사 — 세션 무효 불변식).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class AdminOwnedCommandSpec(
    private val approvals: ApprovalUseCase,
    private val query: ApprovalQuery,
    private val path: ApprovalPath,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val commands: AdminAccountCommandUseCase,
    private val failures: AuthenticationFailureRecorder,
    private val enrollment: EnrollmentUseCase,
    private val roleEvents: AdminRoleEventStore,
    private val executor: AdminOwnedCommandExecutor,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun gen(id: AdminActorId) = storage.load(id).sessionGeneration
    fun decide(approvalId: String, approver: LoginReadyAccounts.LoginReady, code: String = TestTotp.code(approver.secret)) =
        DecideApproval(approvalId, approver.account.id.value, gen(approver.account.id), code, path.context())
    fun request(requester: AdminAccount, kind: String, target: AdminActorId, role: String? = null, cause: String? = null): ApprovalOutcome =
        approvals.request(RequestApproval(requester.id.value, gen(requester.id), kind, target.value, role, cause, path.context()))
    fun statusOf(approvalId: String): String = AdminDbAccounts.root(container).use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", approvalId) as String }
    fun audit(approvalId: String): List<String> = AdminDbAccounts.root(container).use {
        it.column(
            "SELECT CONCAT(action, ':', result_code, ':', COALESCE(executor_actor_id, '-'), ':', COALESCE(detail, '-')) FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id",
            approvalId,
        )
    }
    fun lockedTarget(): AdminAccount {
        val account = storage.save(AdminAccountFixture.enrolledActive())
        repeat(5) { failures.recordFailure(account.id.value, AuthenticationStep.PASSWORD, path.context()) }
        return storage.load(account.id)
    }
    fun reenroll(target: AdminActorId, code: String) {
        val draft = (enrollment.begin(code) as EnrollmentResult.InProgress).draft
        enrollment.complete(
            (enrollment.providePassword(draft, "re-enrolled-password".toCharArray()) as EnrollmentResult.InProgress).draft, null, path.context(),
        ) shouldBe EnrollmentResult.Completed(target.value)
    }

    Given("each of the 6 ADMIN-owned kinds, requested by a SECURITY_ADMIN and approved by a different APPROVER") {
        Then("ROLE_GRANT adds the role with a GRANT event by the approver under this approval; EXECUTED with executor = approver; security_version +1") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            approvals.approve(decide(id, approver)) shouldBe ApprovalOutcome.Executed(id)
            val after = storage.load(target.id)
            (after.activeRoles to (after.securityVersion > target.securityVersion)) shouldBe (setOf(AdminRole.VIEWER) to true)
            storage.inTx { roleEvents.history(target.id) }.last().let { listOf(it.type, it.actor, it.approvalId) } shouldBe
                listOf(RoleEventType.GRANT, RoleEventActor.Admin(approver.account.id), id)
            statusOf(id) shouldBe "EXECUTED"
            audit(id) shouldBe listOf("APPROVAL_REQUESTED:SUCCESS:-:kind=ROLE_GRANT;role=VIEWER", "APPROVAL_EXECUTED:SUCCESS:${approver.account.id.value}:kind=ROLE_GRANT;role=VIEWER")
        }
        Then("ACCOUNT_UNLOCK: LOCKED → ACTIVE with the counter reset") {
            val target = lockedTarget()
            val executed = path.execute("ACCOUNT_UNLOCK", target.id)
            val after = storage.load(executed.let { target.id })
            listOf(after.status, after.failedAttempts, after.lockedAt, after.securityVersion > target.securityVersion) shouldBe listOf(AccountStatus.ACTIVE, 0, null, true)
        }
        Then("ACCOUNT_REACTIVATE: SUSPENDED → the previous status; a locked-then-suspended account returns LOCKED with its original lock time (S3 review m-4)") {
            val target = lockedTarget()
            val admin = storage.securityAdmin()
            commands.suspend(TargetedAccountCommand(admin.id.value, target.id.value, path.context())) shouldBe AccountCommandResult.Succeeded(target.id.value)
            val suspended = storage.load(target.id)
            path.execute("ACCOUNT_REACTIVATE", target.id)
            val after = storage.load(target.id)
            listOf(after.status, after.lockedAt, after.securityVersion > suspended.securityVersion) shouldBe listOf(AccountStatus.LOCKED, target.lockedAt, true)
        }
        Then("PASSWORD_RESET / TOTP_REENROLL: PENDING_ENROLLMENT, every active role SUSPENDed with cause = approval, old codes revoked, one new code only in the approval result") {
            listOf("PASSWORD_RESET", "TOTP_REENROLL").forEach { kind ->
                val target = path.withRolesViaApproval(AdminRole.OPERATOR, AdminRole.VIEWER)
                val eventsBefore = storage.inTx { roleEvents.history(target.id) }.size
                val (after, code, approvalId) = path.credentialReset(kind, target.id)
                listOf(after.status, after.activeRoles, after.suspendedRoles, after.pending?.purpose, after.pending?.basis) shouldBe
                    listOf(AccountStatus.PENDING_ENROLLMENT, emptySet<AdminRole>(), setOf(AdminRole.OPERATOR, AdminRole.VIEWER), EnrollmentPurpose.valueOf(kind), approvalId)
                val events = storage.inTx { roleEvents.history(target.id) }
                events.size shouldBe eventsBefore + 2
                events.takeLast(2).map { it.type to it.cause } shouldBe List(2) { RoleEventType.SUSPEND to approvalId }
                storage.code(EnrollmentCodeHash.of(code))!!.let { it.basis to it.purpose } shouldBe (approvalId to EnrollmentPurpose.valueOf(kind))
            }
        }
        Then("a second reset of a pending account revokes the first code (one usable code per target); the account must set both elements (rev 0.5 m-1)") {
            val target = path.withRolesViaApproval(AdminRole.OPERATOR)
            val (_, first, _) = path.credentialReset("PASSWORD_RESET", target.id)
            val (after, second, secondId) = path.credentialReset("TOTP_REENROLL", target.id)
            enrollment.begin(first) shouldBe EnrollmentResult.Failed(EnrollmentFailure.CODE_INVALID)
            enrollment.begin(second).shouldBeInstanceOf<EnrollmentResult.InProgress>()
            storage.code(EnrollmentCodeHash.of(first))!!.revokedAt shouldNotBe null
            (after.pending?.purpose to after.pending?.basis) shouldBe (EnrollmentPurpose.ENROLL to secondId)
        }
        Then("ROLE_REACTIVATE after re-enrollment restores only the roles still suspended by the cause (a role revoked in between stays revoked)") {
            val target = path.withRolesViaApproval(AdminRole.OPERATOR, AdminRole.VIEWER)
            val (_, code, causeId) = path.credentialReset("PASSWORD_RESET", target.id)
            reenroll(target.id, code)
            val admin = storage.securityAdmin()
            commands.revokeRole(RevokeAdminRole(admin.id.value, target.id.value, "VIEWER", path.context())) shouldBe AccountCommandResult.Succeeded(target.id.value)
            val before = storage.load(target.id)
            path.execute("ROLE_REACTIVATE", target.id, cause = causeId)
            val after = storage.load(target.id)
            listOf(after.activeRoles, after.suspendedRoles, after.securityVersion > before.securityVersion) shouldBe listOf(setOf(AdminRole.OPERATOR), emptySet<AdminRole>(), true)
        }
    }

    Given("the owner re-check contract (D-ADMIN-007 — AdminOwnedCommandExecutor re-validates the evidence itself)") {
        Then("evidence with non-distinct participants, a stale target version or an unknown kind is rejected without change; it runs only inside the caller's TX") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val requester = storage.securityAdmin()
            val approver = storage.bootstrapped(AdminRole.APPROVER)
            fun evidence(kind: String = "ROLE_GRANT", req: AdminActorId = requester.id, apr: AdminActorId = approver.id, version: Long = target.securityVersion) =
                OwnedCommandEvidence(AdminAccountFixture.newApprovalId(), kind, req.value, apr.value, target.id.value, version, if (kind == "ROLE_GRANT") "VIEWER" else null, null)
            listOf(
                evidence(apr = target.id), evidence(req = approver.id), evidence(version = target.securityVersion - 1), evidence(kind = "OPERATOR_CORRECTION_CANCEL"),
            ).map { e -> storage.inTx { executor.execute(e) } } shouldBe listOf(
                OwnedCommandOutcome.Rejected("PARTICIPANTS_NOT_DISTINCT"), OwnedCommandOutcome.Rejected("PARTICIPANTS_NOT_DISTINCT"),
                OwnedCommandOutcome.Rejected("TARGET_VERSION_CHANGED"), OwnedCommandOutcome.Rejected("UNKNOWN_KIND"),
            )
            storage.load(target.id).let { it.activeRoles to it.securityVersion } shouldBe (emptySet<AdminRole>() to target.securityVersion)
            shouldThrow<IllegalTransactionStateException> { executor.execute(evidence()) }
        }
    }

    Given("re-checks inside the approval TX") {
        Then("the target changed after the request (security_version) → INVALIDATED; the approver's TOTP step is still consumed (no reuse)") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val admin = storage.securityAdmin()
            commands.suspend(TargetedAccountCommand(admin.id.value, target.id.value, path.context())) shouldBe AccountCommandResult.Succeeded(target.id.value)
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            approvals.approve(decide(id, approver)) shouldBe ApprovalOutcome.Invalidated("TARGET_CHANGED")
            statusOf(id) shouldBe "INVALIDATED"
            storage.load(approver.account.id).lastTotpStep shouldNotBe 0L
            storage.load(target.id).activeRoles shouldBe emptySet()
        }
        Then("the requester was suspended after the request → INVALIDATED after the approver's TOTP (fail-closed)") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val requester = storage.securityAdmin()
            val id = (request(requester, "ROLE_GRANT", target.id, "VIEWER") as ApprovalOutcome.Created).approvalId
            val admin = storage.securityAdmin()
            commands.suspend(TargetedAccountCommand(admin.id.value, requester.id.value, path.context())) shouldBe AccountCommandResult.Succeeded(requester.id.value)
            approvals.approve(decide(id, ready.bootstrapped(AdminRole.APPROVER))) shouldBe ApprovalOutcome.Invalidated("REQUESTER_NOT_ELIGIBLE")
            storage.load(target.id).activeRoles shouldBe emptySet()
        }
        Then("an approver without APPROVER (or no longer holding it) is denied with no change and no TOTP consumption (403 — n-A)") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val notApprover = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            approvals.approve(decide(id, notApprover)) shouldBe ApprovalOutcome.Denied
            statusOf(id) shouldBe "PENDING"
            storage.load(notApprover.account.id).lastTotpStep shouldBe 0L
            audit(id).last() shouldBe "APPROVAL_DENIED:DENIED:-:reason=APPROVER_NOT_ELIGIBLE"
        }
        Then("self-approval and approving one's own target are refused (SelfApprovalForbidden); a stale session generation is SESSION_REPLACED") {
            val requester = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            path.grant(requester.account.id, AdminRole.APPROVER)
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val own = (request(storage.load(requester.account.id), "ROLE_GRANT", target.id, "VIEWER") as ApprovalOutcome.Created).approvalId
            approvals.approve(decide(own, requester)) shouldBe ApprovalOutcome.Refused("SELF_APPROVAL")
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val aboutApprover = path.request("ROLE_GRANT", approver.account.id, "VIEWER")
            approvals.approve(decide(aboutApprover, approver)) shouldBe ApprovalOutcome.Refused("APPROVER_IS_TARGET")
            val other = ready.bootstrapped(AdminRole.APPROVER)
            approvals.approve(decide(own, other).copy(sessionGeneration = gen(other.account.id) + 1)) shouldBe ApprovalOutcome.SessionReplaced
            listOf(statusOf(own), statusOf(aboutApprover)) shouldBe listOf("PENDING", "PENDING")
        }
        Then("expiry is judged with the TX clock: an expired request becomes EXPIRED on approval; the expiry job closes the rest") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val other = path.request("ACCOUNT_UNLOCK", lockedTarget().id)
            AdminDbAccounts.root(container).use {
                it.update("UPDATE admin_approval_request SET expires_at = requested_at + INTERVAL 1000 MICROSECOND WHERE approval_id IN (?, ?)", id, other)
            }
            approvals.approve(decide(id, ready.bootstrapped(AdminRole.APPROVER))) shouldBe ApprovalOutcome.Expired
            (approvals.expireDue() >= 1) shouldBe true
            listOf(statusOf(id), statusOf(other)) shouldBe listOf("EXPIRED", "EXPIRED")
            audit(other).last() shouldBe "APPROVAL_EXPIRED:EXPIRED:-:kind=ACCOUNT_UNLOCK"
        }
    }

    Given("creation rules") {
        Then("a second PENDING of the same kind and target is DUPLICATE_PENDING (audited separately); another kind for the same target is allowed") {
            val target = lockedTarget()
            path.request("ACCOUNT_UNLOCK", target.id)
            request(storage.securityAdmin(), "ACCOUNT_UNLOCK", target.id) shouldBe ApprovalOutcome.Rejected("DUPLICATE_PENDING")
            request(storage.securityAdmin(), "PASSWORD_RESET", target.id).shouldBeInstanceOf<ApprovalOutcome.Created>()
        }
        Then("non-SECURITY_ADMIN requesters are denied; ineligible targets and unknown targets are rejected; self-targeting is refused") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val approverOnly = storage.bootstrapped(AdminRole.APPROVER)
            val admin = storage.securityAdmin()
            listOf(
                request(approverOnly, "ROLE_GRANT", target.id, "VIEWER"),
                request(admin, "ACCOUNT_UNLOCK", target.id),
                request(admin, "ROLE_GRANT", AdminAccountFixture.newActorId(), "VIEWER"),
                request(admin, "ROLE_GRANT", admin.id, "VIEWER"),
            ) shouldBe listOf(
                ApprovalOutcome.Denied, ApprovalOutcome.Rejected("NOT_ELIGIBLE"), ApprovalOutcome.Rejected("TARGET_NOT_FOUND"), ApprovalOutcome.Refused("SELF_TARGET"),
            )
        }
        Then("withdraw: only the requester, only while PENDING; afterwards every command is ALREADY_DECIDED") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val requester = storage.securityAdmin()
            val id = (request(requester, "ROLE_GRANT", target.id, "VIEWER") as ApprovalOutcome.Created).approvalId
            val stranger = storage.securityAdmin()
            approvals.withdraw(WithdrawApproval(id, stranger.id.value, gen(stranger.id), path.context())) shouldBe ApprovalOutcome.Refused("NOT_REQUESTER")
            approvals.withdraw(WithdrawApproval(id, requester.id.value, gen(requester.id), path.context())) shouldBe ApprovalOutcome.Closed("WITHDRAWN")
            approvals.approve(decide(id, ready.bootstrapped(AdminRole.APPROVER))) shouldBe ApprovalOutcome.AlreadyDecided
            audit(id) shouldBe listOf(
                "APPROVAL_REQUESTED:SUCCESS:-:kind=ROLE_GRANT;role=VIEWER", "APPROVAL_DENIED:REFUSED:-:kind=ROLE_GRANT;role=VIEWER;reason=NOT_REQUESTER",
                "APPROVAL_WITHDRAWN:WITHDRAWN:-:kind=ROLE_GRANT;role=VIEWER",
            )
        }
        Then("withdrawing an expired request expires it with an audit row, like approve and reject (every state change is audited)") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val requester = storage.securityAdmin()
            val id = (request(requester, "ROLE_GRANT", target.id, "VIEWER") as ApprovalOutcome.Created).approvalId
            AdminDbAccounts.root(container).use { it.update("UPDATE admin_approval_request SET expires_at = requested_at + INTERVAL 1000 MICROSECOND WHERE approval_id = ?", id) }
            approvals.withdraw(WithdrawApproval(id, requester.id.value, gen(requester.id), path.context())) shouldBe ApprovalOutcome.Expired
            statusOf(id) shouldBe "EXPIRED"
            audit(id).last() shouldBe "APPROVAL_EXPIRED:EXPIRED:-:kind=ROLE_GRANT;role=VIEWER"
        }
    }

    Given("step-up TOTP (§4.7.4)") {
        Then("a wrong code rolls the approval back and counts the failure in a separate TX; failures sum with login failures and lock the approver (StepUpFailureCounts)") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val right = TestTotp.code(approver.secret)
            val wrong = ((right.toInt() + 500_000) % 1_000_000).toString().padStart(6, '0')
            repeat(4) { approvals.approve(decide(id, approver, wrong)) shouldBe ApprovalOutcome.StepUpFailed }
            (statusOf(id) to storage.load(approver.account.id).failedAttempts) shouldBe ("PENDING" to 4)
            failures.recordFailure(approver.account.id.value, AuthenticationStep.PASSWORD, path.context())
            storage.load(approver.account.id).status shouldBe AccountStatus.LOCKED
            approvals.approve(decide(id, approver, right)) shouldBe ApprovalOutcome.Denied
        }
        Then("reject consumes a TOTP step too and closes the request as REJECTED") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            approvals.reject(decide(id, approver)) shouldBe ApprovalOutcome.Closed("REJECTED")
            storage.load(approver.account.id).lastTotpStep shouldNotBe 0L
            approvals.reject(decide(id, ready.bootstrapped(AdminRole.APPROVER))) shouldBe ApprovalOutcome.AlreadyDecided
        }
        Then("reject by an account without APPROVER is denied before TOTP with no change; withdraw with a stale session generation is SESSION_REPLACED (S5 review m-3)") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val requester = storage.securityAdmin()
            val id = (request(requester, "ROLE_GRANT", target.id, "VIEWER") as ApprovalOutcome.Created).approvalId
            val notApprover = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            approvals.reject(decide(id, notApprover)) shouldBe ApprovalOutcome.Denied
            (statusOf(id) to storage.load(notApprover.account.id).lastTotpStep) shouldBe ("PENDING" to 0L)
            approvals.withdraw(WithdrawApproval(id, requester.id.value, gen(requester.id) + 1, path.context())) shouldBe ApprovalOutcome.SessionReplaced
            statusOf(id) shouldBe "PENDING"
        }
    }

    Given("reset code disclosure (§4.7.7 — ResetCodeDisclosure)") {
        Then("the code appears only in the approval result: not in the approval detail, list, audit rows or approval row") {
            val target = path.withRolesViaApproval(AdminRole.OPERATOR)
            val since = AdminDbAccounts.root(container).use { (it.scalar("SELECT COALESCE(MAX(event_id), 0) FROM admin_audit_event") as Number).toLong() }
            val requester = storage.securityAdmin()
            val approvalId = path.request("TOTP_REENROLL", target.id, requester = requester)
            val code = requireNotNull((path.approve(approvalId) as ApprovalOutcome.Executed).enrollmentCode)
            // 요청자 본인의 "내 요청" 목록 — 실제로 이 요청이 들어 있음을 먼저 확인(빈 목록으로 통과하지 않게, S5 리뷰 m-1).
            val mine = query.list(requester.id.value, pendingOnly = false)!!
            mine.map { it.approvalId } shouldContain approvalId
            val allAuditSince = AdminDbAccounts.root(container).use {
                it.column(
                    "SELECT CONCAT_WS('|', action, COALESCE(detail, ''), COALESCE(reason, ''), correlation_id) FROM admin_audit_event WHERE event_id > ?", since,
                )
            }
            (allAuditSince.size >= 2) shouldBe true
            val texts = listOf(query.detail(requester.id.value, approvalId).toString(), mine.toString()) +
                allAuditSince + AdminDbAccounts.root(container).use { c ->
                    c.column("SELECT CAST(JSON_OBJECT('row', target_snapshot, 'code', COALESCE(result_code, '')) AS CHAR) FROM admin_approval_request WHERE approval_id = ?", approvalId)
                }
            texts.none { it.contains(code) } shouldBe true
        }
    }

    Given("role reactivation separation (H-A — RoleReactivate/RoleRule rows)") {
        Then("the cause reset's requester or approver can neither request nor approve the reactivation; a re-enrollment must come first") {
            val r1 = ready.bootstrapped(AdminRole.SECURITY_ADMIN).also { path.grant(it.account.id, AdminRole.APPROVER) }
            val p1 = ready.bootstrapped(AdminRole.APPROVER).also { path.grant(it.account.id, AdminRole.SECURITY_ADMIN) }
            val target = path.withRolesViaApproval(AdminRole.OPERATOR)
            val causeId = (request(storage.load(r1.account.id), "PASSWORD_RESET", target.id) as ApprovalOutcome.Created).approvalId
            val executed = approvals.approve(decide(causeId, p1)).shouldBeInstanceOf<ApprovalOutcome.Executed>()
            val r2 = storage.securityAdmin()
            request(r2, "ROLE_REACTIVATE", target.id, cause = causeId) shouldBe ApprovalOutcome.Rejected("NOT_ELIGIBLE")
            reenroll(target.id, requireNotNull(executed.enrollmentCode))
            listOf(
                request(storage.load(r1.account.id), "ROLE_REACTIVATE", target.id, cause = causeId),
                request(storage.load(p1.account.id), "ROLE_REACTIVATE", target.id, cause = causeId),
            ) shouldBe List(2) { ApprovalOutcome.Rejected("CAUSE_PARTICIPANT") }
            val reactivation = (request(r2, "ROLE_REACTIVATE", target.id, cause = causeId) as ApprovalOutcome.Created).approvalId
            listOf(approvals.approve(decide(reactivation, r1, TestTotp.code(r1.secret, Instant.now().plusSeconds(30)))), approvals.approve(decide(reactivation, p1, TestTotp.code(p1.secret, Instant.now().plusSeconds(30))))) shouldBe
                List(2) { ApprovalOutcome.Refused("CAUSE_PARTICIPANT") }
            approvals.approve(decide(reactivation, ready.bootstrapped(AdminRole.APPROVER))).shouldBeInstanceOf<ApprovalOutcome.Executed>()
            storage.load(target.id).activeRoles shouldBe setOf(AdminRole.OPERATOR)
        }
    }
})
