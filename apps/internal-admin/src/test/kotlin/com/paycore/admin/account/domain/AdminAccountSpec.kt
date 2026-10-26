package com.paycore.admin.account.domain

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountFixture.NOW
import com.paycore.admin.account.AdminAccountFixture.OTHER_PASSWORD_HASH
import com.paycore.admin.account.AdminAccountFixture.OTHER_TOTP
import com.paycore.admin.account.AdminAccountFixture.PASSWORD_HASH
import com.paycore.admin.account.AdminAccountFixture.TOTP
import com.paycore.admin.account.AdminAccountFixture.applied
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 관리자 계정 Aggregate 상태 전이(§4.3.1·§4.3.5·§4.5·§4.7.7, D-ADMIN1-001·003~005·009·011, rev 0.3 H-B·n-E). DB·Spring 없음.
 * 세대(session_generation)는 기존 세션 즉시 무효화 값, security_version은 대기 중 승인 무효화 값(사건 표는 [SecurityVersionEventsSpec]).
 */
class AdminAccountSpec : BehaviorSpec({

    val policy = LoginAttemptPolicy(maxFailures = 3)
    val later = NOW.plusSeconds(60)

    fun rejectedReason(change: AccountChange): AccountRejection = change.shouldBeInstanceOf<AccountChange.Rejected>().reason

    Given("a new account") {
        val account = AdminAccountFixture.pending()
        Then("it waits for enrollment with purpose ENROLL and its issue basis, without roles or credentials") {
            listOf(account.status, account.pending?.purpose, account.passwordHash, account.totp, account.activeRoles, account.securityVersion, account.sessionGeneration) shouldBe
                listOf(AccountStatus.PENDING_ENROLLMENT, EnrollmentPurpose.ENROLL, null, null, emptySet<AdminRole>(), 0L, 0L)
        }
    }

    Given("enrollment completion (D-ADMIN1-011 — only the elements of the purpose)") {
        Then("ENROLL sets password and TOTP, activates, clears the pending reason and bumps generation and security version") {
            val account = AdminAccountFixture.enrolledActive()
            listOf(account.status, account.pending, account.passwordHash, account.totp, account.lastTotpStep, account.sessionGeneration, account.securityVersion) shouldBe
                listOf(AccountStatus.ACTIVE, null, PASSWORD_HASH, TOTP, 1000L, 1L, 1L)
        }
        Then("a different purpose or basis than the account's pending reason is not applicable (H-B)") {
            val account = AdminAccountFixture.pending()
            val basis = requireNotNull(account.pending).basis
            listOf(
                rejectedReason(account.completeEnrollment(PendingEnrollment(EnrollmentPurpose.RECOVERY, basis), PASSWORD_HASH, NewTotp(TOTP, 1), NOW)),
                rejectedReason(account.completeEnrollment(PendingEnrollment(EnrollmentPurpose.ENROLL, "enc_other"), PASSWORD_HASH, NewTotp(TOTP, 1), NOW)),
            ) shouldBe listOf(AccountRejection.ENROLLMENT_NOT_APPLICABLE, AccountRejection.ENROLLMENT_NOT_APPLICABLE)
        }
        Then("an ACTIVE, LOCKED or SUSPENDED account cannot complete an enrollment") {
            val active = AdminAccountFixture.enrolledActive()
            val suspended = applied(active.suspend(AdminAccountFixture.newActorId(), NOW))
            listOf(active, suspended).map {
                rejectedReason(it.completeEnrollment(PendingEnrollment(EnrollmentPurpose.ENROLL, "enc_x"), PASSWORD_HASH, NewTotp(TOTP, 1), NOW))
            } shouldBe listOf(AccountRejection.ENROLLMENT_NOT_APPLICABLE, AccountRejection.ENROLLMENT_NOT_APPLICABLE)
        }
        Then("missing or extra elements for the purpose are an invalid step") {
            val account = AdminAccountFixture.pending()
            val pending = requireNotNull(account.pending)
            listOf(
                rejectedReason(account.completeEnrollment(pending, PASSWORD_HASH, null, NOW)),
                rejectedReason(account.completeEnrollment(pending, null, NewTotp(TOTP, 1), NOW)),
            ) shouldBe listOf(AccountRejection.ENROLLMENT_STEP_INVALID, AccountRejection.ENROLLMENT_STEP_INVALID)
        }
        Then("PASSWORD_RESET changes only the password and keeps the TOTP credential; TOTP_REENROLL changes only the TOTP") {
            val executor = AdminAccountFixture.newActorId()
            val active = AdminAccountFixture.enrolledActive()
            val passwordReset = applied(active.beginCredentialReset(EnrollmentPurpose.PASSWORD_RESET, "apr_RESET1", AdminAccountFixture.APPROVAL_REQUESTER, executor, NOW))
            val afterPassword = applied(passwordReset.completeEnrollment(requireNotNull(passwordReset.pending), OTHER_PASSWORD_HASH, null, later))
            val totpReset = applied(active.beginCredentialReset(EnrollmentPurpose.TOTP_REENROLL, "apr_RESET2", AdminAccountFixture.APPROVAL_REQUESTER, executor, NOW))
            val afterTotp = applied(totpReset.completeEnrollment(requireNotNull(totpReset.pending), null, NewTotp(OTHER_TOTP, 2000), later))
            listOf(afterPassword.passwordHash, afterPassword.totp, afterPassword.status, afterTotp.passwordHash, afterTotp.totp, afterTotp.lastTotpStep) shouldBe
                listOf(OTHER_PASSWORD_HASH, TOTP, AccountStatus.ACTIVE, PASSWORD_HASH, OTHER_TOTP, 2000L)
            rejectedReason(passwordReset.completeEnrollment(requireNotNull(passwordReset.pending), OTHER_PASSWORD_HASH, NewTotp(OTHER_TOTP, 1), later)) shouldBe
                AccountRejection.ENROLLMENT_STEP_INVALID
        }
    }

    Given("a credential reset (PASSWORD_RESET / TOTP_REENROLL executed by an approval — D-ADMIN1-009)") {
        val executor = AdminAccountFixture.newActorId()
        val admin = AdminAccountFixture.withRolesViaApproval(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)
        val reset = admin.beginCredentialReset(EnrollmentPurpose.PASSWORD_RESET, "apr_RESET9", AdminAccountFixture.APPROVAL_REQUESTER, executor, NOW).shouldBeInstanceOf<AccountChange.Applied>()
        Then("it moves to PENDING_ENROLLMENT with the approval as basis, invalidates the reset credential and suspends every active role (history kept)") {
            val a = reset.account
            listOf(a.status, a.pending, a.passwordHash, a.totp, a.activeRoles, a.suspendedRoles) shouldBe listOf(
                AccountStatus.PENDING_ENROLLMENT, PendingEnrollment(EnrollmentPurpose.PASSWORD_RESET, "apr_RESET9"), null, TOTP, emptySet<AdminRole>(),
                setOf(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER),
            )
            reset.events.map { Triple(it.role, it.type, it.cause) }.toSet() shouldBe setOf(
                Triple(AdminRole.SECURITY_ADMIN, RoleEventType.SUSPEND, "apr_RESET9"), Triple(AdminRole.APPROVER, RoleEventType.SUSPEND, "apr_RESET9"),
            )
            (a.sessionGeneration to a.securityVersion) shouldBe ((admin.sessionGeneration + 1) to (admin.securityVersion + 1))
        }
        Then("re-enrollment activates the account but the roles stay suspended (separate ROLE_REACTIVATE approval)") {
            val reenrolled = applied(reset.account.completeEnrollment(requireNotNull(reset.account.pending), OTHER_PASSWORD_HASH, null, later))
            (reenrolled.status to reenrolled.activeRoles) shouldBe (AccountStatus.ACTIVE to emptySet())
            reenrolled.suspendedRoles shouldBe setOf(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)
        }
        Then("a SUSPENDED account cannot be reset; only reset purposes are accepted (S5: PENDING_ENROLLMENT is now resettable — rev 0.4 m-2 2-person reissue)") {
            val suspended = applied(AdminAccountFixture.enrolledActive().suspend(executor, NOW))
            rejectedReason(suspended.beginCredentialReset(EnrollmentPurpose.PASSWORD_RESET, "apr_X", AdminAccountFixture.APPROVAL_REQUESTER, executor, NOW)) shouldBe AccountRejection.NOT_ELIGIBLE
            shouldThrow<IllegalArgumentException> { admin.beginCredentialReset(EnrollmentPurpose.ENROLL, "apr_X", AdminAccountFixture.APPROVAL_REQUESTER, executor, NOW) }
        }
        Then("a PENDING_ENROLLMENT account is reissued with the new approval as basis; missing credentials force ENROLL (both elements — rev 0.5 m-1) and active roles are suspended") {
            val bootstrapPending = applied(AdminAccountFixture.pending().grantRole(AdminRole.APPROVER, RoleEventActor.SystemBootstrap, null, NOW))
            val change = bootstrapPending.beginCredentialReset(EnrollmentPurpose.PASSWORD_RESET, "apr_REISSUE", AdminAccountFixture.APPROVAL_REQUESTER, executor, NOW) as AccountChange.Applied
            listOf(change.account.pending, change.account.activeRoles, change.account.suspendedRoles, change.account.securityVersion) shouldBe
                listOf(PendingEnrollment(EnrollmentPurpose.ENROLL, "apr_REISSUE"), emptySet<AdminRole>(), setOf(AdminRole.APPROVER), bootstrapPending.securityVersion + 1)
            val resetOnce = applied(admin.beginCredentialReset(EnrollmentPurpose.TOTP_REENROLL, "apr_FIRST", AdminAccountFixture.APPROVAL_REQUESTER, executor, NOW))
            // 재발급 대상은 첫 재설정으로 TOTP가 이미 무효화됐으므로(확정 자격 하나 없음) 종류와 관계없이 ENROLL로 두 요소를 모두 설정한다(rev 0.5 m-1).
            applied(resetOnce.beginCredentialReset(EnrollmentPurpose.TOTP_REENROLL, "apr_SECOND", AdminAccountFixture.APPROVAL_REQUESTER, executor, NOW)).pending shouldBe
                PendingEnrollment(EnrollmentPurpose.ENROLL, "apr_SECOND")
        }
        Then("D-ADMIN1-013: a later reset re-appends SUSPEND (cause = the new reset) for roles already suspended, so the latest SUSPEND cause is always the latest reset") {
            val resetOnce = applied(admin.beginCredentialReset(EnrollmentPurpose.PASSWORD_RESET, "apr_ONE", AdminAccountFixture.APPROVAL_REQUESTER, executor, NOW))
            val again = resetOnce.beginCredentialReset(EnrollmentPurpose.PASSWORD_RESET, "apr_TWO", AdminAccountFixture.APPROVAL_REQUESTER, executor, NOW).shouldBeInstanceOf<AccountChange.Applied>()
            again.events.map { Triple(it.role, it.type, it.cause) }.toSet() shouldBe setOf(
                Triple(AdminRole.SECURITY_ADMIN, RoleEventType.SUSPEND, "apr_TWO"), Triple(AdminRole.APPROVER, RoleEventType.SUSPEND, "apr_TWO"),
            )
            (again.account.activeRoles to again.account.suspendedRoles) shouldBe (emptySet<AdminRole>() to setOf(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER))
        }
    }

    Given("ACCOUNT_UNLOCK / ACCOUNT_REACTIVATE / ROLE_REACTIVATE executions (S5 — §4.7.6·§4.7.7)") {
        val active = AdminAccountFixture.withRolesViaApproval(AdminRole.OPERATOR, AdminRole.VIEWER)
        val executor = AdminAccountFixture.newActorId()
        fun locked(account: AdminAccount = active) = (account.recordAuthenticationFailure(LoginAttemptPolicy(1), NOW) as AuthenticationFailure.Locked).account
        Then("unlock: LOCKED → ACTIVE, counter 0, lock time cleared, generation and security version bumped; anything else is not eligible") {
            val lockedAccount = locked()
            val unlocked = applied(lockedAccount.unlock(NOW))
            listOf(unlocked.status, unlocked.failedAttempts, unlocked.lockedAt, unlocked.sessionGeneration, unlocked.securityVersion) shouldBe
                listOf(AccountStatus.ACTIVE, 0, null, lockedAccount.sessionGeneration + 1, lockedAccount.securityVersion + 1)
            rejectedReason(active.unlock(NOW)) shouldBe AccountRejection.NOT_ELIGIBLE
        }
        Then("reactivate: back to the pre-suspension status; a locked-then-suspended account returns LOCKED with its ORIGINAL lock time (S3 review m-4)") {
            val lockedAccount = locked()
            val suspended = applied(lockedAccount.suspend(executor, NOW.plusSeconds(60)))
            suspended.lockedAt shouldBe lockedAccount.lockedAt
            val back = applied(suspended.reactivate(NOW.plusSeconds(120)))
            listOf(back.status, back.lockedAt, back.statusBeforeSuspend) shouldBe listOf(AccountStatus.LOCKED, lockedAccount.lockedAt, null)
            applied(applied(active.suspend(executor, NOW)).reactivate(NOW)).status shouldBe AccountStatus.ACTIVE
            rejectedReason(active.reactivate(NOW)) shouldBe AccountRejection.NOT_ELIGIBLE
        }
        Then("role reactivation restores only roles still suspended by the cause; nothing to restore or not ACTIVE is refused") {
            val reset = applied(active.beginCredentialReset(EnrollmentPurpose.PASSWORD_RESET, "apr_CAUSE", AdminAccountFixture.APPROVAL_REQUESTER, executor, NOW))
            rejectedReason(reset.reactivateRoles("apr_CAUSE", setOf(AdminRole.OPERATOR), executor, "apr_R1", NOW)) shouldBe AccountRejection.NOT_ELIGIBLE
            val reenrolled = applied(reset.completeEnrollment(reset.pending!!, OTHER_PASSWORD_HASH, null, NOW))
            val change = reenrolled.reactivateRoles("apr_CAUSE", setOf(AdminRole.OPERATOR), executor, "apr_R1", NOW) as AccountChange.Applied
            listOf(change.account.activeRoles, change.account.suspendedRoles, change.events.map { it.type to it.cause }) shouldBe
                listOf(setOf(AdminRole.OPERATOR), setOf(AdminRole.VIEWER), listOf(RoleEventType.REACTIVATE to "apr_CAUSE"))
            rejectedReason(reenrolled.reactivateRoles("apr_CAUSE", emptySet(), executor, "apr_R2", NOW)) shouldBe AccountRejection.NOTHING_TO_REACTIVATE
        }
    }

    Given("authentication failures (D-ADMIN1-005 — summed, no automatic unlock)") {
        val active = AdminAccountFixture.enrolledActive()
        Then("failures below the limit only count (no generation or security version change)") {
            val once = active.recordAuthenticationFailure(policy, NOW).shouldBeInstanceOf<AuthenticationFailure.Counted>().account
            listOf(once.failedAttempts, once.status, once.sessionGeneration, once.securityVersion) shouldBe
                listOf(1, AccountStatus.ACTIVE, active.sessionGeneration, active.securityVersion)
        }
        Then("the limit-th failure locks the account, ends sessions and bumps the security version") {
            var account = active
            repeat(2) { account = account.recordAuthenticationFailure(policy, NOW).shouldBeInstanceOf<AuthenticationFailure.Counted>().account }
            val locked = account.recordAuthenticationFailure(policy, later).shouldBeInstanceOf<AuthenticationFailure.Locked>().account
            listOf(locked.status, locked.failedAttempts, locked.lockedAt, locked.sessionGeneration, locked.securityVersion) shouldBe
                listOf(AccountStatus.LOCKED, 3, later, active.sessionGeneration + 1, active.securityVersion + 1)
        }
        Then("failures on LOCKED, SUSPENDED or PENDING accounts are not counted (state is judged first)") {
            val locked = generateSequence(active) { (it.recordAuthenticationFailure(policy, NOW) as? AuthenticationFailure.Counted)?.account }.last()
                .recordAuthenticationFailure(policy, NOW).shouldBeInstanceOf<AuthenticationFailure.Locked>().account
            val suspended = applied(active.suspend(AdminAccountFixture.newActorId(), NOW))
            listOf(locked, suspended, AdminAccountFixture.pending()).map { it.recordAuthenticationFailure(policy, NOW)::class } shouldBe
                List(3) { AuthenticationFailure.NotCounted::class }
        }
        Then("an ACTIVE account already at or over the limit (policy lowered) is not counted again and not locked by the step itself (S4 review m-4)") {
            val active = AdminAccountFixture.enrolledActive()
            val atFour = (1..4).fold(active) { a, _ -> (a.recordAuthenticationFailure(LoginAttemptPolicy(5), NOW) as AuthenticationFailure.Counted).account }
            val lowered = LoginAttemptPolicy(3)
            atFour.hasReachedFailureLimit(lowered) shouldBe true
            atFour.recordAuthenticationFailure(lowered, NOW).shouldBeInstanceOf<AuthenticationFailure.NotCounted>().account.failedAttempts shouldBe 4
        }
        Then("a successful login resets the counter without bumping the security version") {
            val failed = active.recordAuthenticationFailure(policy, NOW).shouldBeInstanceOf<AuthenticationFailure.Counted>().account
            val ok = failed.recordAuthenticationSuccess(later)
            (ok.failedAttempts to ok.securityVersion) shouldBe (0 to active.securityVersion)
        }
    }

    Given("TOTP steps (§4.3.4 — only steps after the last used step)") {
        val active = AdminAccountFixture.enrolledActive()
        Then("a later step is accepted and recorded; the same or an earlier step is a reuse") {
            val accepted = applied(active.acceptTotpStep(1001, NOW))
            listOf(accepted.lastTotpStep, rejectedReason(accepted.acceptTotpStep(1001, NOW)), rejectedReason(accepted.acceptTotpStep(999, NOW))) shouldBe
                listOf(1001L, AccountRejection.TOTP_STEP_REUSED, AccountRejection.TOTP_STEP_REUSED)
            accepted.securityVersion shouldBe active.securityVersion
        }
        Then("a new login session bumps only the session generation (new login wins — D-ADMIN1-001)") {
            val renewed = active.startNewSession(NOW)
            (renewed.sessionGeneration to renewed.securityVersion) shouldBe ((active.sessionGeneration + 1) to active.securityVersion)
        }
    }

    Given("suspension (1-person, immediate — D-ADMIN1-003)") {
        val requester = AdminAccountFixture.newActorId()
        Then("ACTIVE, LOCKED and PENDING accounts are suspended keeping the previous status; the pending reason is cleared (H-B)") {
            val pending = AdminAccountFixture.pending()
            val suspendedPending = applied(pending.suspend(requester, NOW))
            val suspendedActive = applied(AdminAccountFixture.enrolledActive().suspend(requester, NOW))
            listOf(suspendedPending.status, suspendedPending.statusBeforeSuspend, suspendedPending.pending, suspendedActive.statusBeforeSuspend) shouldBe
                listOf(AccountStatus.SUSPENDED, AccountStatus.PENDING_ENROLLMENT, null, AccountStatus.ACTIVE)
            (suspendedPending.sessionGeneration to suspendedPending.securityVersion) shouldBe ((pending.sessionGeneration + 1) to (pending.securityVersion + 1))
        }
        Then("suspending oneself is refused (n-E) and an already suspended account is not suspended twice") {
            val account = AdminAccountFixture.enrolledActive()
            listOf(rejectedReason(account.suspend(account.id, NOW)), rejectedReason(applied(account.suspend(requester, NOW)).suspend(requester, NOW))) shouldBe
                listOf(AccountRejection.SELF_SUSPEND, AccountRejection.ALREADY_SUSPENDED)
        }
    }

    Given("role revocation (1-person, immediate — D-ADMIN1-004)") {
        val requester = AdminAccountFixture.newActorId()
        val admin = AdminAccountFixture.withRolesViaApproval(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)
        Then("an active role is removed with a REVOKE event, sessions end and the security version is bumped") {
            val change = admin.revokeRole(requester, AdminRole.APPROVER, NOW).shouldBeInstanceOf<AccountChange.Applied>()
            change.account.activeRoles shouldBe setOf(AdminRole.SECURITY_ADMIN)
            change.events.map { it.role to it.type } shouldBe listOf(AdminRole.APPROVER to RoleEventType.REVOKE)
            (change.account.sessionGeneration to change.account.securityVersion) shouldBe ((admin.sessionGeneration + 1) to (admin.securityVersion + 1))
        }
        Then("a suspended role can be revoked too (it can then never be reactivated)") {
            val reset = applied(admin.beginCredentialReset(EnrollmentPurpose.TOTP_REENROLL, "apr_R", AdminAccountFixture.APPROVAL_REQUESTER, requester, NOW))
            applied(reset.revokeRole(requester, AdminRole.APPROVER, NOW)).suspendedRoles shouldBe setOf(AdminRole.SECURITY_ADMIN)
        }
        Then("revoking a role one does not hold or one's own role is refused") {
            listOf(rejectedReason(admin.revokeRole(requester, AdminRole.VIEWER, NOW)), rejectedReason(admin.revokeRole(admin.id, AdminRole.APPROVER, NOW))) shouldBe
                listOf(AccountRejection.ROLE_NOT_HELD, AccountRejection.SELF_ROLE_CHANGE)
        }
    }

    Given("role grants (domain transition used by bootstrap now and by ROLE_GRANT approvals in S5)") {
        Then("a granted role becomes active with a GRANT event; an active or suspended role is not granted again") {
            val account = AdminAccountFixture.enrolledActive()
            val change = account.grantRole(AdminRole.VIEWER, RoleEventActor.Admin(AdminAccountFixture.newActorId()), "apr_G1", NOW).shouldBeInstanceOf<AccountChange.Applied>()
            change.account.activeRoles shouldBe setOf(AdminRole.VIEWER)
            change.events.single().let { listOf(it.type, it.approvalId) } shouldBe listOf(RoleEventType.GRANT, "apr_G1")
            rejectedReason(change.account.grantRole(AdminRole.VIEWER, RoleEventActor.SystemBootstrap, null, NOW)) shouldBe AccountRejection.ROLE_ALREADY_HELD
        }
    }

    Given("ENROLL code reissue by one SECURITY_ADMIN (rev 0.4 H-1 — only accounts without any active or suspended role)") {
        Then("a role-less pending account gets the new basis and a security version bump") {
            val pending = AdminAccountFixture.pending()
            val reissued = applied(pending.reissueEnrollCode("enc_NEW", AdminAccountFixture.FIXTURE_CREATOR, NOW))
            (reissued.pending to reissued.securityVersion) shouldBe (PendingEnrollment(EnrollmentPurpose.ENROLL, "enc_NEW") to (pending.securityVersion + 1))
        }
        Then("role-bearing or non-pending accounts are refused") {
            val bootstrapPending = AdminAccountFixture.applied(
                AdminAccountFixture.pending().grantRole(AdminRole.APPROVER, RoleEventActor.SystemBootstrap, null, NOW),
            )
            listOf(rejectedReason(bootstrapPending.reissueEnrollCode("enc_NEW", AdminAccountFixture.FIXTURE_CREATOR, NOW)), rejectedReason(AdminAccountFixture.enrolledActive().reissueEnrollCode("enc_NEW", AdminAccountFixture.FIXTURE_CREATOR, NOW))) shouldBe
                listOf(AccountRejection.ROLE_BEARING_ACCOUNT, AccountRejection.NOT_PENDING_ENROLLMENT)
        }
        Then("a pending account holding only suspended roles (credential reset) is role-bearing too: no new basis, no security version bump") {
            val reset = applied(
                AdminAccountFixture.bootstrapped(AdminRole.APPROVER).beginCredentialReset(EnrollmentPurpose.PASSWORD_RESET, "apr_RESET", AdminAccountFixture.APPROVAL_REQUESTER, AdminAccountFixture.newActorId(), NOW),
            )
            (reset.activeRoles to reset.suspendedRoles) shouldBe (emptySet<AdminRole>() to setOf(AdminRole.APPROVER))
            rejectedReason(reset.reissueEnrollCode("enc_NEW", AdminAccountFixture.FIXTURE_CREATOR, NOW)) shouldBe AccountRejection.ROLE_BEARING_ACCOUNT
        }
    }

    Given("the credential basis (D-ADMIN1-014·015 — who issued the code that makes the current credential)") {
        Then("it is set by code issuance only (create → creator, ENROLL reissue → issuer, reset → requester + approver) and kept through enrollment, suspension and role changes") {
            val creator = AdminAccountFixture.newActorId()
            val created = AdminAccountFixture.pending(creator = creator)
            created.credentialBasis.issuers shouldBe setOf(creator)
            val reissuer = AdminAccountFixture.newActorId()
            val reissued = applied(created.reissueEnrollCode("enc_RE", reissuer, NOW))
            // D-98(재리뷰 m-2 판정 (b)): 재발급은 자격을 바꾸지 않아 등록 완료 전까지 이전 생성자도 배제, 완료 때 실제로 쓴 코드의 발급자만 남는다.
            (reissued.credentialBasis.basis to reissued.credentialBasis.issuers) shouldBe ("enc_RE" to setOf(reissuer, creator))
            val enrolled = AdminAccountFixture.enroll(reissued)
            val suspended = applied(applied(enrolled.grantRole(AdminRole.VIEWER, RoleEventActor.SystemBootstrap, null, NOW)).suspend(AdminAccountFixture.newActorId(), NOW))
            listOf(enrolled.credentialBasis.issuers, suspended.credentialBasis.issuers) shouldBe List(2) { setOf(reissuer) }
            val (requester, approver) = AdminAccountFixture.newActorId() to AdminAccountFixture.newActorId()
            val reset = applied(enrolled.beginCredentialReset(EnrollmentPurpose.TOTP_REENROLL, "apr_BASIS", requester, approver, NOW))
            listOf(reset.credentialBasis.basis, reset.credentialBasis.requester, reset.credentialBasis.approver) shouldBe listOf("apr_BASIS", requester, approver)
            (reset.credentialBasis.isIssuer(approver) to reset.credentialBasis.isIssuer(creator)) shouldBe (true to false)
            AdminAccountFixture.bootstrapped(AdminRole.APPROVER).credentialBasis.issuers shouldBe emptySet()
        }
    }

    Given("offline recovery task A (S6 — §4.9.2, D-ADMIN1-010·012, D-99)") {
        val basis = RecoveryBasis.of(7)
        Then("records the held SECURITY_ADMIN/APPROVER (active or suspended), suspends every role (re-appending SUSPEND for already suspended ones), invalidates both credentials, system basis") {
            val admin = AdminAccountFixture.withRolesViaApproval(AdminRole.SECURITY_ADMIN, AdminRole.OPERATOR, AdminRole.APPROVER)
            val partlyReset = applied(applied(admin.revokeRole(AdminAccountFixture.newActorId(), AdminRole.OPERATOR, NOW)).grantRole(AdminRole.OPERATOR, RoleEventActor.SystemBootstrap, null, NOW))
            val reset = applied(partlyReset.beginCredentialReset(EnrollmentPurpose.PASSWORD_RESET, "apr_PRE", AdminAccountFixture.APPROVAL_REQUESTER, AdminAccountFixture.newActorId(), NOW))
            reset.recoveryRecordableRoles shouldBe setOf(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)
            val change = reset.beginRecovery(basis, NOW).shouldBeInstanceOf<AccountChange.Applied>()
            val a = change.account
            listOf(a.status, a.pending, a.passwordHash, a.totp, a.activeRoles, a.suspendedRoles, a.credentialBasis.issuers) shouldBe listOf(
                AccountStatus.PENDING_ENROLLMENT, PendingEnrollment(EnrollmentPurpose.RECOVERY, basis), null, null, emptySet<AdminRole>(),
                setOf(AdminRole.SECURITY_ADMIN, AdminRole.OPERATOR, AdminRole.APPROVER), emptySet<AdminActorId>(),
            )
            change.events.map { Triple(it.role, it.type, it.cause) }.toSet() shouldBe setOf(AdminRole.SECURITY_ADMIN, AdminRole.OPERATOR, AdminRole.APPROVER)
                .map { Triple(it, RoleEventType.SUSPEND, basis) }.toSet()
            change.events.map { it.actor }.toSet() shouldBe setOf(RoleEventActor.SystemRecovery)
            (a.sessionGeneration to a.securityVersion) shouldBe ((reset.sessionGeneration + 1) to (reset.securityVersion + 1))
        }
        Then("a SUSPENDED account is not a recovery target (fail-closed — recovery is not a suspension lift)") {
            rejectedReason(applied(AdminAccountFixture.enrolledActive().suspend(AdminAccountFixture.newActorId(), NOW)).beginRecovery(basis, NOW)) shouldBe AccountRejection.NOT_ELIGIBLE
        }
        Then("on completion only the given recorded roles that are still suspended are reactivated by SYSTEM_RECOVERY with the episode as cause") {
            val admin = AdminAccountFixture.withRolesViaApproval(AdminRole.APPROVER, AdminRole.VIEWER)
            val recovering = applied(admin.beginRecovery(basis, NOW))
            val enrolled = applied(recovering.completeEnrollment(requireNotNull(recovering.pending), OTHER_PASSWORD_HASH, NewTotp(OTHER_TOTP, 3000), later))
            val restored = enrolled.reactivateRecoveredRoles(setOf(AdminRole.APPROVER), basis, later).shouldBeInstanceOf<AccountChange.Applied>()
            (restored.account.activeRoles to restored.account.suspendedRoles) shouldBe (setOf(AdminRole.APPROVER) to setOf(AdminRole.VIEWER))
            restored.events.map { listOf(it.role, it.type, it.actor, it.cause) } shouldBe listOf(listOf(AdminRole.APPROVER, RoleEventType.REACTIVATE, RoleEventActor.SystemRecovery, basis))
            rejectedReason(enrolled.reactivateRecoveredRoles(setOf(AdminRole.SECURITY_ADMIN), basis, later)) shouldBe AccountRejection.NOTHING_TO_REACTIVATE
            rejectedReason(recovering.reactivateRecoveredRoles(setOf(AdminRole.APPROVER), basis, later)) shouldBe AccountRejection.NOT_ELIGIBLE
        }
    }

    Given("reconstitution from stored rows (invalid fixtures named by intent)") {
        val base = AdminAccountFixture.enrolledActive()
        fun invalid(status: AccountStatus = base.status, before: AccountStatus? = null, pending: PendingEnrollment? = null, hash: PasswordHash? = base.passwordHash,
            totp: TotpCredential? = base.totp, lockedAt: java.time.Instant? = null, active: Set<AdminRole> = emptySet(), suspended: Set<AdminRole> = emptySet(),
            basis: CredentialBasis = base.credentialBasis) =
            AdminAccount.reconstitute(
                base.id, base.loginName, base.displayName, status, before, pending, basis, hash, totp, base.lastTotpStep, base.failedAttempts, lockedAt, active, suspended,
                base.sessionGeneration, base.securityVersion, base.createdAt, base.updatedAt,
            )
        Then("impossible combinations are refused") {
            listOf(
                runCatching { invalid(totp = null) }, // ACTIVE without TOTP
                runCatching { invalid(hash = null) }, // ACTIVE without password
                runCatching { invalid(status = AccountStatus.LOCKED) }, // LOCKED without lock time
                runCatching { invalid(status = AccountStatus.SUSPENDED) }, // SUSPENDED without previous status
                runCatching { invalid(before = AccountStatus.ACTIVE) }, // previous status outside SUSPENDED
                runCatching { invalid(pending = PendingEnrollment(EnrollmentPurpose.ENROLL, "enc_x")) }, // pending reason outside PENDING_ENROLLMENT
                runCatching { invalid(active = setOf(AdminRole.VIEWER), suspended = setOf(AdminRole.VIEWER)) }, // a role both active and suspended
                // D-ADMIN1-014: 대기 코드 근거 ≠ 자격 발급 근거, 계정 자신이 발급 근거 참여자.
                runCatching { invalid(status = AccountStatus.PENDING_ENROLLMENT, hash = null, totp = null, pending = PendingEnrollment(EnrollmentPurpose.ENROLL, "enc_other")) },
                runCatching { invalid(basis = CredentialBasis.oneShotCommand("enc_self", base.id)) },
                runCatching { CredentialBasis("apr_x", null, base.id) }, // approver without requester
            ).map { it.exceptionOrNull()?.javaClass } shouldBe List(10) { IllegalArgumentException::class.java }
        }
        Then("a valid stored account round-trips") {
            invalid(active = base.activeRoles).status shouldBe AccountStatus.ACTIVE
        }
    }
})
