package com.paycore.admin.account.domain

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountFixture.NOW
import com.paycore.admin.account.AdminAccountFixture.applied
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * `security_version` 증가 사건 표(§4.7.5, D-13 — 무효 범위를 넓히는 쪽만 구현 결정). 증가 행은 +1, 제외 행(인증 활동)은 불변.
 * 열 분류 누락 감지(표에 없는 새 열)는 DB 시험 [com.paycore.admin.account.adapter.persistence.AdminPersistenceMappingSpec]이 Entity 열 목록과 대조한다.
 * S5·S6 전이(잠금 해제·정지 해제·재활성·복구)의 행은 그 단계에서 이 표에 추가한다.
 */
class SecurityVersionEventsSpec : FunSpec({

    val policy = LoginAttemptPolicy(maxFailures = 2)
    val requester = AdminAccountFixture.newActorId()

    fun bump(before: AdminAccount, after: AdminAccount): Long = after.securityVersion - before.securityVersion

    test("increasing events: state transitions, role events, credential changes, enrollment code issue") {
        val active = AdminAccountFixture.enrolledActive()
        val admin = AdminAccountFixture.withRolesViaApproval(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)
        val pending = AdminAccountFixture.pending()
        val counted = (active.recordAuthenticationFailure(policy, NOW) as AuthenticationFailure.Counted).account
        val locked = counted.recordAuthenticationFailure(policy, NOW).account
        val reset = applied(admin.beginCredentialReset(EnrollmentPurpose.PASSWORD_RESET, "apr_S", AdminAccountFixture.APPROVAL_REQUESTER, requester, NOW))
        mapOf(
            "→LOCKED (limit reached)" to bump(counted, locked),
            "→SUSPENDED" to bump(active, applied(active.suspend(requester, NOW))),
            "→PENDING_ENROLLMENT (reset, roles SUSPEND, credential invalidated)" to bump(admin, reset),
            "→ACTIVE (enrollment completed, credential set)" to bump(pending, AdminAccountFixture.enroll(pending)),
            "role GRANT" to bump(active, applied(active.grantRole(AdminRole.VIEWER, RoleEventActor.Admin(AdminAccountFixture.APPROVAL_APPROVER), "apr_SV_GRANT", NOW))),
            "role REVOKE" to bump(admin, applied(admin.revokeRole(requester, AdminRole.APPROVER, NOW))),
            "ENROLL code reissue" to bump(pending, applied(pending.reissueEnrollCode("enc_R", AdminAccountFixture.FIXTURE_CREATOR, NOW))),
        ) shouldBe mapOf(
            "→LOCKED (limit reached)" to 1L, "→SUSPENDED" to 1L, "→PENDING_ENROLLMENT (reset, roles SUSPEND, credential invalidated)" to 1L,
            "→ACTIVE (enrollment completed, credential set)" to 1L, "role GRANT" to 1L, "role REVOKE" to 1L, "ENROLL code reissue" to 1L,
        )
    }

    test("excluded events (authentication activity): failure count below the limit, counter reset, TOTP step, new session generation") {
        val active = AdminAccountFixture.enrolledActive()
        val counted = (active.recordAuthenticationFailure(policy, NOW) as AuthenticationFailure.Counted).account
        mapOf(
            "failure below limit" to bump(active, counted),
            "counter reset" to bump(counted, counted.recordAuthenticationSuccess(NOW)),
            "TOTP step" to bump(active, applied(active.acceptTotpStep(5000, NOW))),
            "new session (generation only)" to bump(active, active.startNewSession(NOW)),
        ) shouldBe mapOf("failure below limit" to 0L, "counter reset" to 0L, "TOTP step" to 0L, "new session (generation only)" to 0L)
    }
})
