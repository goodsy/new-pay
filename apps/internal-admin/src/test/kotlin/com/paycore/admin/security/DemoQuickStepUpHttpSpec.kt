package com.paycore.admin.security

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.SwitchableAuditStore
import com.paycore.admin.support.SwitchableAuditStoreConfig
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer

/**
 * 데모 간편 로그인 세션의 재인증 생략(ADMIN-DEMO-LOGIN-001 결정 ③ — D-ADMIN-AUTH-DEMO-001 ③, Q-DEMOLOGIN-3). 켜짐(DEMO + enabled) **그리고** 세션 표시(간편 로그인)일 때만
 * 승인·반려·마스킹 해제의 TOTP 검증을 건너뛴다: 실패 계수·TOTP 단계 불변, 감사 `STEP_UP_SKIPPED`·`DEMO_SKIPPED`(같은 상관 ID). 2인 승인 규칙·역할 검사·감사는 그대로.
 * 같은 켜짐 설정에서도 일반 로그인 세션은 기존과 같다(빈 TOTP → 403 STEP_UP_FAILED, 계수 +1). 꺼짐 설정은 DemoQuickLoginDisabledHttpSpec.
 */
@SpringBootTest(properties = ["paycore.admin.demo-quick-login.enabled=true", DemoQuickLoginTestConfig.ACCOUNTS])
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class, SwitchableAuditStoreConfig::class)
class DemoQuickStepUpHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
    @Suppress("unused") private val auditStore: SwitchableAuditStore,
) : BehaviorSpec({

    val approver = ready.bootstrapped(AdminRole.APPROVER, login = "su-approver")
    val normalApprover = ready.bootstrapped(AdminRole.APPROVER, login = "su-normal")
    val both = ready.bootstrapped(AdminRole.SECURITY_ADMIN, login = "su-both").also { path.grant(it.account.id, AdminRole.APPROVER) }
    val unmasker = ready.withoutRoles(login = "su-unmask").also { path.grant(it.account.id, AdminRole.UNMASK) }

    fun json(vararg pairs: Pair<String, String>) = AdminHttpClient.json(*pairs)
    fun quick(loginName: String): AdminHttpClient = AdminHttpClient(mockMvc).also {
        it.csrf()
        it.perform(post("/api/auth/demo-quick-login").contentType(MediaType.APPLICATION_JSON).content(json("loginName" to loginName))).status shouldBe 200
    }
    fun normal(account: LoginReadyAccounts.LoginReady): AdminHttpClient =
        AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun approvalIdOf(response: MockHttpServletResponse) = Regex("\"approvalId\":\"(apr_[0-9A-Z]+)\"").find(response.contentAsString)!!.groupValues[1]
    fun create(requester: AdminHttpClient): String = approvalIdOf(
        requester.perform(
            post("/api/approvals").contentType(MediaType.APPLICATION_JSON)
                .content(json("kind" to "ROLE_GRANT", "targetActorId" to storage.save(AdminAccountFixture.enrolledActive()).id.value, "grantedRole" to "VIEWER")),
        ).also { it.status shouldBe 201 },
    )
    fun decide(c: AdminHttpClient, id: String, action: String, code: String = "") =
        c.perform(post("/api/approvals/$id/$action").contentType(MediaType.APPLICATION_JSON).content(json("totpCode" to code)))
    fun root() = AdminDbAccounts.root(container)
    fun skipped(actorId: String): List<String> = root().use {
        it.column("SELECT CONCAT(result_code, '|', detail, '|', correlation_id) FROM admin_audit_event WHERE actor_id = ? AND action = 'STEP_UP_SKIPPED' ORDER BY event_id", actorId)
    }
    fun correlationOf(approvalId: String, action: String): String = root().use {
        it.scalar("SELECT correlation_id FROM admin_audit_event WHERE approval_id = ? AND action = ? ORDER BY event_id DESC LIMIT 1", approvalId, action) as String
    }
    val sa = normal(ready.bootstrapped(AdminRole.SECURITY_ADMIN))

    Given("an APPROVER signed in with the demo quick login") {
        Then("approve without a TOTP executes the approval; the skip is audited with the same correlation; failure count and TOTP step are unchanged") {
            val id = create(sa)
            val before = storage.load(approver.account.id)
            val c = quick("su-approver")
            decide(c, id, "approve").let { it.status to it.contentAsString.contains("\"status\":\"EXECUTED\"") } shouldBe (200 to true)
            val after = storage.load(approver.account.id)
            (after.failedAttempts to after.lastTotpStep) shouldBe (before.failedAttempts to before.lastTotpStep)
            skipped(approver.account.id.value).last() shouldBe "DEMO_SKIPPED|stepUp=DEMO_SKIPPED;method=DEMO_QUICK|" + correlationOf(id, "APPROVAL_EXECUTED")
        }
        Then("reject without a TOTP is accepted (200 REJECTED) and audited as skipped too") {
            val id = create(sa)
            val count = skipped(approver.account.id.value).size
            decide(quick("su-approver"), id, "reject").let { it.status to it.contentAsString } shouldBe (200 to """{"status":"REJECTED"}""")
            skipped(approver.account.id.value).size shouldBe count + 1
        }
        Then("two-person rules stay: the requester cannot approve their own request even in a demo session (403 SELF_APPROVAL, not executed, no skip audit)") {
            val self = quick("su-both")
            val id = create(self)
            val count = skipped(both.account.id.value).size
            decide(self, id, "approve").let { it.status to it.contentAsString } shouldBe (403 to """{"error":"SELF_APPROVAL"}""")
            skipped(both.account.id.value).size shouldBe count
            root().use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) } shouldBe "PENDING"
        }
        Then("role checks stay: an account without APPROVER is refused (403) in a demo session") {
            val id = create(sa)
            decide(quick("su-unmask"), id, "approve").status shouldBe 403
            root().use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) } shouldBe "PENDING"
        }
    }

    Given("an UNMASK holder signed in with the demo quick login") {
        Then("unmask without a TOTP passes (204), keeps its reason audit and records the skip; the failure count is unchanged") {
            val before = storage.load(unmasker.account.id).failedAttempts
            quick("su-unmask").perform(
                post("/api/unmask").contentType(MediaType.APPLICATION_JSON).content(json("targetRef" to "payment:7", "reason" to "데모 확인", "totpCode" to "")),
            ).status shouldBe 204
            root().use {
                it.column("SELECT CONCAT(action, ':', result_code, ':', reason) FROM admin_audit_event WHERE actor_id = ? AND action = 'UNMASK_REQUESTED'", unmasker.account.id.value)
            } shouldBe listOf("UNMASK_REQUESTED:SUCCESS:데모 확인")
            skipped(unmasker.account.id.value).size shouldBe 1
            storage.load(unmasker.account.id).failedAttempts shouldBe before
        }
    }

    Given("a normal (password + TOTP) session while the demo quick login is on") {
        Then("nothing changes: an empty TOTP is 403 STEP_UP_FAILED and counts one failure; unmask without TOTP is 400 TOTP_REQUIRED; no skip audit") {
            val id = create(sa)
            val c = normal(normalApprover)
            decide(c, id, "approve").let { it.status to it.contentAsString } shouldBe (403 to """{"error":"STEP_UP_FAILED"}""")
            storage.load(normalApprover.account.id).failedAttempts shouldBe 1
            skipped(normalApprover.account.id.value) shouldBe emptyList()
            val u = normal(unmasker)
            u.perform(post("/api/unmask").contentType(MediaType.APPLICATION_JSON).content(json("targetRef" to "payment:8", "reason" to "확인", "totpCode" to "")))
                .let { it.status to it.contentAsString } shouldBe (400 to """{"error":"TOTP_REQUIRED"}""")
        }
    }
})
