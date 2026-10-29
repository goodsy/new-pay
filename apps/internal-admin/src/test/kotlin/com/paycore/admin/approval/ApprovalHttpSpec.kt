package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant

/**
 * 승인·마스킹 해제 HTTP(§4.7·§4.5.3 — `ResetCodeDisclosureSpec` HTTP 행, `SelfApprovalForbiddenSpec`, `UnmaskFrameworkSpec`). 실제 2단계 로그인 세션(세대는 서버 세션에서 읽음),
 * CSRF 헤더, 고정 JSON code, 재설정 코드는 승인 응답 본문에만 1회(`no-store`).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class ApprovalHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun loggedIn(account: LoginReadyAccounts.LoginReady, at: Instant = Instant.now()) =
        AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret, at).status shouldBe 200 }
    fun json(vararg pairs: Pair<String, String>) = AdminHttpClient.json(*pairs)
    fun approvalIdOf(body: String) = Regex("\"approvalId\":\"(apr_[0-9A-Z]+)\"").find(body)!!.groupValues[1]

    Given("approval target snapshots (D-ADMIN1-020 — Q-S8-C)") {
        Then("no viewing role (VIEWER, OPERATOR, APPROVER, SECURITY_ADMIN) sees the target login name in the list or the detail; the stored snapshot has none") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val name = target.loginName.value
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val viewer = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.VIEWER) }
            val operator = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR) }
            listOf(viewer, operator, ready.bootstrapped(AdminRole.APPROVER), ready.bootstrapped(AdminRole.SECURITY_ADMIN)).forEach { account ->
                val c = loggedIn(account)
                listOf(c.perform(get("/api/approvals?view=pending")), c.perform(get("/api/approvals/$id"))).forEach { response ->
                    response.status shouldBe 200
                    response.contentAsString shouldContain id
                    response.contentAsString shouldNotContain name
                    response.contentAsString shouldNotContain "loginName"
                }
            }
            AdminDbAccounts.root(container).use { it.column("SELECT target_snapshot FROM admin_approval_request WHERE approval_id = ?", id) }.single() shouldNotContain name
        }
    }

    Given("a PASSWORD_RESET requested and approved over HTTP") {
        Then("the code is in the approval response only (no-store); the request detail, the list and the audit never contain it; self-approval is 403") {
            val requester = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val target = path.withRolesViaApproval(AdminRole.OPERATOR)
            val sa = loggedIn(requester)
            val created = sa.perform(post("/api/approvals").contentType(MediaType.APPLICATION_JSON).content(json("kind" to "PASSWORD_RESET", "targetActorId" to target.id.value)))
            created.status shouldBe 201
            val id = approvalIdOf(created.contentAsString)
            sa.perform(post("/api/approvals/$id/approve").contentType(MediaType.APPLICATION_JSON).content(json("totpCode" to TestTotp.code(requester.secret, Instant.now().plusSeconds(30)))))
                .let { it.status to it.contentAsString } shouldBe (403 to """{"error":"SELF_APPROVAL"}""")
            val apr = loggedIn(approver)
            val approved = apr.perform(post("/api/approvals/$id/approve").contentType(MediaType.APPLICATION_JSON).content(json("totpCode" to TestTotp.code(approver.secret, Instant.now().plusSeconds(30)))))
            approved.status shouldBe 200
            approved.getHeader("Cache-Control") shouldBe "no-store"
            val code = Regex("\"enrollmentCode\":\"([A-Z2-7]{32})\"").find(approved.contentAsString)!!.groupValues[1]
            listOf(sa.perform(get("/api/approvals/$id")).contentAsString, sa.perform(get("/api/approvals?view=mine")).contentAsString, sa.perform(get("/api/audit")).contentAsString)
                .forEach { it shouldNotContain code }
            AdminDbAccounts.root(container).use { it.column("SELECT COALESCE(detail, '') FROM admin_audit_event WHERE approval_id = ?", id) }.none { it.contains(code) } shouldBe true
        }
        Then("a request without the CSRF header is 403 and creates nothing; an unauthenticated approval is 401") {
            val requester = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            val target = storage.save(AdminAccountFixture.enrolledActive())
            loggedIn(requester).perform(
                post("/api/approvals").contentType(MediaType.APPLICATION_JSON).content(json("kind" to "PASSWORD_RESET", "targetActorId" to target.id.value)), xsrf = false,
            ).status shouldBe 403
            AdminHttpClient(mockMvc).perform(post("/api/approvals/apr_00000000000000000000000000/approve").contentType(MediaType.APPLICATION_JSON).content("{}")).status shouldBe 403
        }
    }

    Given("a malformed cause on ROLE_REACTIVATE (S6 review m-2)") {
        Then("it is 400 INVALID_INPUT before any transaction (no approval row, no audit row)") {
            val requester = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            val target = storage.save(AdminAccountFixture.enrolledActive())
            loggedIn(requester).perform(
                post("/api/approvals").contentType(MediaType.APPLICATION_JSON)
                    .content(json("kind" to "ROLE_REACTIVATE", "targetActorId" to target.id.value, "causeApprovalId" to "episode:x;DROP")),
            ).let { it.status to it.contentAsString } shouldBe (400 to """{"error":"INVALID_INPUT"}""")
            AdminDbAccounts.root(container).use {
                it.column("SELECT approval_id FROM admin_approval_request WHERE target_actor_id = ?", target.id.value) +
                    it.column("SELECT action FROM admin_audit_event WHERE target_actor_id = ?", target.id.value)
            } shouldBe emptyList()
        }
    }

    Given("ADMIN-PAYMENT-OPS-001 M-1 (§4.10 1행): the owner-service kind on the single-TX request endpoint") {
        Then("it is 400 WRONG_EXECUTION_MODEL at kind resolution (no approval row, no audit row) — only the new endpoint creates it") {
            val requester = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            val target = storage.save(AdminAccountFixture.enrolledActive())
            loggedIn(requester).perform(
                post("/api/approvals").contentType(MediaType.APPLICATION_JSON).content(json("kind" to "OPERATOR_CORRECTION_CANCEL", "targetActorId" to target.id.value)),
            ).let { it.status to it.contentAsString } shouldBe (400 to """{"error":"WRONG_EXECUTION_MODEL"}""")
            AdminDbAccounts.root(container).use {
                it.column("SELECT approval_id FROM admin_approval_request WHERE target_actor_id = ?", target.id.value) +
                    it.column("SELECT action FROM admin_audit_event WHERE target_actor_id = ?", target.id.value)
            } shouldBe emptyList()
        }
    }

    Given("reject, withdraw and list over HTTP") {
        Then("APPROVER rejects with TOTP (200 REJECTED), the requester withdraws (200 WITHDRAWN), later decisions are 409 ALREADY_DECIDED; a role-less account cannot list (403)") {
            val requester = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val sa = loggedIn(requester)
            fun create() = approvalIdOf(
                sa.perform(post("/api/approvals").contentType(MediaType.APPLICATION_JSON).content(json("kind" to "ROLE_GRANT", "targetActorId" to storage.save(AdminAccountFixture.enrolledActive()).id.value, "grantedRole" to "VIEWER")))
                    .also { it.status shouldBe 201 }.contentAsString,
            )
            val rejected = create()
            val withdrawn = create()
            val apr = loggedIn(approver)
            val code = TestTotp.code(approver.secret, Instant.now().plusSeconds(30))
            apr.perform(post("/api/approvals/$rejected/reject").contentType(MediaType.APPLICATION_JSON).content(json("totpCode" to code)))
                .let { it.status to it.contentAsString } shouldBe (200 to """{"status":"REJECTED"}""")
            sa.perform(post("/api/approvals/$withdrawn/withdraw")).let { it.status to it.contentAsString } shouldBe (200 to """{"status":"WITHDRAWN"}""")
            listOf(rejected, withdrawn).forEach { id ->
                sa.perform(post("/api/approvals/$id/withdraw")).let { it.status to it.contentAsString } shouldBe (409 to """{"error":"ALREADY_DECIDED"}""")
            }
            loggedIn(ready.withoutRoles()).perform(get("/api/approvals")).let { it.status to it.contentAsString } shouldBe (403 to """{"error":"FORBIDDEN"}""")
        }
    }

    Given("the unmask framework (AC10 — UnmaskFramework)") {
        Then("a reason over the length limit is 400 REASON_INVALID; a denied attempt (no UNMASK) is audited as DENIED without the reason text") {
            val viewer = ready.bootstrapped(AdminRole.APPROVER)
            val c = loggedIn(viewer)
            c.perform(post("/api/unmask").contentType(MediaType.APPLICATION_JSON).content(json("targetRef" to "payment:1", "reason" to "x".repeat(501), "totpCode" to "123456")))
                .let { it.status to it.contentAsString } shouldBe (400 to """{"error":"REASON_INVALID"}""")
            c.perform(post("/api/unmask").contentType(MediaType.APPLICATION_JSON).content(json("targetRef" to "payment:2", "reason" to "dispute", "totpCode" to "123456"))).status shouldBe 403
            AdminDbAccounts.root(container).use {
                it.column("SELECT CONCAT(action, ':', result_code, ':', detail, ':', COALESCE(reason, '-')) FROM admin_audit_event WHERE actor_id = ? AND action = 'UNMASK_REQUESTED'", viewer.account.id.value)
            } shouldBe listOf("UNMASK_REQUESTED:DENIED:target=payment:2:-")
        }
        Then("no UNMASK role → 403; missing reason → 400; missing TOTP → 400; wrong TOTP → 403 STEP_UP_FAILED; valid → 204 with one audit row keeping the reason") {
            val viewer = ready.bootstrapped(AdminRole.APPROVER)
            loggedIn(viewer).perform(post("/api/unmask").contentType(MediaType.APPLICATION_JSON).content(json("targetRef" to "payment:1", "reason" to "dispute", "totpCode" to "123456")))
                .let { it.status to it.contentAsString } shouldBe (403 to """{"error":"FORBIDDEN"}""")
            val unmasker = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.UNMASK) }
            val c = loggedIn(unmasker)
            fun send(reason: String, code: String) = c.perform(post("/api/unmask").contentType(MediaType.APPLICATION_JSON).content(json("targetRef" to "payment:1", "reason" to reason, "totpCode" to code)))
            val next = TestTotp.code(unmasker.secret, Instant.now().plusSeconds(30))
            val wrong = ((next.toInt() + 500_000) % 1_000_000).toString().padStart(6, '0')
            listOf(send("", next), send("dispute check", ""), send("dispute check", wrong)).map { it.status to it.contentAsString } shouldBe listOf(
                400 to """{"error":"REASON_REQUIRED"}""", 400 to """{"error":"TOTP_REQUIRED"}""", 403 to """{"error":"STEP_UP_FAILED"}""",
            )
            send("고객 분쟁 확인 #1234", next).status shouldBe 204
            AdminDbAccounts.root(container).use {
                it.column("SELECT CONCAT(action, ':', detail, ':', reason) FROM admin_audit_event WHERE actor_id = ? AND action = 'UNMASK_REQUESTED'", unmasker.account.id.value)
            } shouldBe listOf("UNMASK_REQUESTED:target=payment:1:고객 분쟁 확인 #1234")
            storage.load(unmasker.account.id).failedAttempts shouldBe 1
        }
    }
})
