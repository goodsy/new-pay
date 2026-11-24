package com.paycore.admin.security

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import org.testcontainers.mysql.MySQLContainer
import java.util.Base64
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * 데모 간편 로그인 꺼짐(ADMIN-DEMO-LOGIN-001 §2 — T-1): 시험 기본 설정(DEMO 모드, enabled 없음)에서는 엔드포인트가 등록되지 않는다 — 익명·로그인 모두 404.
 * 일반 로그인은 그대로(LoginHttpSpec).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class DemoQuickLoginDisabledHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
    private val approvals: ApprovalUseCase,
) : BehaviorSpec({

    fun quick(c: AdminHttpClient, xsrf: Boolean = true) =
        c.perform(post("/api/auth/demo-quick-login").contentType(MediaType.APPLICATION_JSON).content(AdminHttpClient.json("loginName" to "demo-operator")), xsrf = xsrf)

    Given("DEMO mode without demo-quick-login.enabled") {
        Then("the options and login endpoints do not exist (404 NOT_FOUND) for an anonymous session; the POST still needs CSRF (403)") {
            val c = AdminHttpClient(mockMvc)
            c.csrf()
            c.perform(get("/api/auth/demo-quick-login")).let { it.status to it.contentAsString } shouldBe (404 to """{"error":"NOT_FOUND"}""")
            quick(c).let { it.status to it.contentAsString } shouldBe (404 to """{"error":"NOT_FOUND"}""")
            quick(c, xsrf = false).status shouldBe 403
            c.perform(get("/api/auth/me")).status shouldBe 401
        }
        Then("a signed-in session also gets 404, and the normal two-step login is unaffected") {
            val account = ready.bootstrapped(AdminRole.APPROVER)
            val c = AdminHttpClient(mockMvc)
            c.login(account.loginName, account.password, account.secret).status shouldBe 200
            c.perform(get("/api/auth/demo-quick-login")).status shouldBe 404
            c.perform(get("/api/auth/me")).status shouldBe 200
        }
    }

    Given("decision ③ and the session marker when the feature is off (review: off = exactly as before, MINOR-B)") {
        Then("a session carrying the demo login-method marker (left over from an enabled run) is discarded on its next request: 401 SESSION_EXPIRED, row removed") {
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val c = AdminHttpClient(mockMvc)
            c.login(approver.loginName, approver.password, approver.secret).status shouldBe 200
            val sessionId = String(Base64.getDecoder().decode(c.sessionCookie!!))
            AdminDbAccounts.root(container).use {
                it.update(
                    "INSERT INTO admin_session_attributes (SESSION_PRIMARY_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES) " +
                        "SELECT PRIMARY_ID, 'paycore.admin.loginMethod', ? FROM admin_session WHERE SESSION_ID = ?",
                    "DEMO_QUICK".toByteArray(), sessionId,
                )
            } shouldBe 1
            c.perform(get("/api/auth/me")).let { it.status to it.contentAsString } shouldBe (401 to """{"error":"SESSION_EXPIRED"}""")
            AdminDbAccounts.root(container).use { (it.scalar("SELECT COUNT(*) FROM admin_session WHERE SESSION_ID = ?", sessionId) as Number).toLong() } shouldBe 0L
            // 표시 없는 일반 세션은 그대로 유효하다.
            val other = ready.bootstrapped(AdminRole.APPROVER)
            AdminHttpClient(mockMvc).also { it.login(other.loginName, other.password, other.secret).status shouldBe 200 }.perform(get("/api/auth/me")).status shouldBe 200
        }
        Then("defense in depth: even a demo-session context reaching the use case gets no skip — an empty TOTP is STEP_UP_FAILED, counts a failure, no skip audit") {
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val id = path.request("ROLE_GRANT", storage.save(AdminAccountFixture.enrolledActive()).id, "VIEWER")
            approvals.approve(
                DecideApproval(id, approver.account.id.value, storage.load(approver.account.id).sessionGeneration, "", AdminRequestContext("corr-off-demo", null, demoQuickSession = true)),
            ) shouldBe ApprovalOutcome.StepUpFailed
            storage.load(approver.account.id).failedAttempts shouldBe 1
            AdminDbAccounts.root(container).use {
                (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE actor_id = ? AND action = 'STEP_UP_SKIPPED'", approver.account.id.value) as Number).toLong()
            } shouldBe 0L
        }
    }
})
