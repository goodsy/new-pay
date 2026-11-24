package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.security.DemoQuickLoginTestConfig
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubFinancialOpsServer
import com.paycore.admin.support.StubPaymentOpsServer
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
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * 결정 ③의 나머지 2-TX 경로(CLEANUP-PONY-001 5 — ADMIN-DEMO-LOGIN-001 §8): OwnerServiceApprovalService(PAYMENT 운영자 보정 취소)와 AcquiringOpsApprovalService(재매입).
 * 데모 간편 로그인 승인자는 TOTP 없이 EXECUTED, `STEP_UP_SKIPPED` 감사는 TX1 `APPROVAL_EXECUTING`과 같은 상관 ID, 실패 계수 0. 같은 켜짐 설정의 일반 세션은 기존대로
 * (빈 TOTP → 403 STEP_UP_FAILED, PENDING, 생략 감사 없음). PAYMENT·FINANCIAL은 실제 HTTP 대역. 컨텍스트는 DemoQuick* spec과 같은 설정(공유).
 */
@SpringBootTest(properties = ["paycore.admin.demo-quick-login.enabled=true", DemoQuickLoginTestConfig.ACCOUNTS])
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class, SwitchableAuditStoreConfig::class)
class DemoQuickOwnerStepUpHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    StubFinancialOpsServer.start()
    val json = JsonMapper.builder().build()
    val acquiringReplies = ConcurrentHashMap<String, (ByteArray) -> StubFinancialOpsServer.Reply>()
    StubFinancialOpsServer.replyWith(StubFinancialOpsServer.EXECUTE) { body ->
        acquiringReplies[json.readTree(body).get("approvalId").asString()]?.invoke(body) ?: StubFinancialOpsServer.Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}")
    }
    afterSpec { StubFinancialOpsServer.clearDynamic(StubFinancialOpsServer.EXECUTE) }

    fun root() = AdminDbAccounts.root(container)
    fun withRole(role: AdminRole, login: String? = null) = ready.withoutRoles(login = login).also { path.grant(it.account.id, role) }
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun quick(loginName: String) = AdminHttpClient(mockMvc).also {
        it.csrf()
        it.perform(post("/api/auth/demo-quick-login").contentType(MediaType.APPLICATION_JSON).content(AdminHttpClient.json("loginName" to loginName))).status shouldBe 200
    }
    fun idOf(response: MockHttpServletResponse): String {
        response.status shouldBe 201
        return json.readTree(response.contentAsByteArray).get("approvalId").asString()
    }
    fun approveWithoutCode(c: AdminHttpClient, id: String) =
        c.perform(post("/api/approvals/$id/approve").contentType(MediaType.APPLICATION_JSON).content(AdminHttpClient.json("totpCode" to "")))
    fun skipped(actorId: String): List<String> = root().use {
        it.column("SELECT correlation_id FROM admin_audit_event WHERE actor_id = ? AND action = 'STEP_UP_SKIPPED' ORDER BY event_id", actorId)
    }
    fun executingCorrelation(id: String) = root().use {
        it.scalar("SELECT correlation_id FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_EXECUTING'", id) as String
    }
    fun status(id: String) = root().use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun failures(actorId: String) = root().use { (it.scalar("SELECT failed_attempts FROM admin_account WHERE actor_id = ?", actorId) as Number).toInt() }

    val operator = loggedIn(withRole(AdminRole.OPERATOR))
    val ownerApprover = withRole(AdminRole.APPROVER, login = "ow-approver")
    val acquiringApprover = withRole(AdminRole.APPROVER, login = "aq-approver")
    var seq = System.nanoTime() % 1_000_000L * 100
    fun paymentId(): String = "pay_01JD" + (++seq).toString().padStart(22, '0')
    fun cancelRequest(): String {
        val pay = paymentId()
        StubPaymentOpsServer.targetFound(pay)
        val body = """{"paymentId":"$pay","amountMinor":7000,"purposeKind":"INCIDENT_OR_RECON_CORRECTION","purposeReference":"RECON-2026-0001","reason":"duplicate charge correction"}"""
        return idOf(operator.perform(post("/api/approvals/operator-correction-cancel").contentType(MediaType.APPLICATION_JSON).content(body)))
    }

    Given("OwnerServiceApprovalService (PAYMENT operator correction cancel)") {
        Then("a demo quick login APPROVER executes it without a TOTP; the skip audit shares the APPROVAL_EXECUTING correlation; failure count 0") {
            val id = cancelRequest()
            StubPaymentOpsServer.execution(id, StubPaymentOpsServer.Reply(200, "{\"cancellationId\":\"pcn_01JD000000000000000000HT01\",\"status\":\"IN_PROGRESS\",\"existing\":false}"))
            approveWithoutCode(quick("ow-approver"), id).let { it.status to it.contentAsString } shouldBe (200 to """{"approvalId":"$id","status":"EXECUTED"}""")
            skipped(ownerApprover.account.id.value) shouldBe listOf(executingCorrelation(id))
            failures(ownerApprover.account.id.value) shouldBe 0
        }
        Then("a normal session is unchanged: an empty TOTP is 403 STEP_UP_FAILED, PENDING, one failure, no skip audit") {
            val id = cancelRequest()
            val normal = withRole(AdminRole.APPROVER)
            approveWithoutCode(loggedIn(normal), id).let { it.status to it.contentAsString } shouldBe (403 to """{"error":"STEP_UP_FAILED"}""")
            status(id) shouldBe "PENDING"
            failures(normal.account.id.value) shouldBe 1
            skipped(normal.account.id.value) shouldBe emptyList()
        }
    }

    Given("AcquiringOpsApprovalService (re-acquisition)") {
        Then("a demo quick login APPROVER executes it without a TOTP; the skip audit shares the APPROVAL_EXECUTING correlation; failure count 0") {
            val fileId = "ACQ.D${System.nanoTime() % 100_000_000L}.TEST.20261105.001"
            StubFinancialOpsServer.reply(
                StubFinancialOpsServer.targetsPath(fileId),
                StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.targetsJson(fileId, StubFinancialOpsServer.targetItemJson(1), StubFinancialOpsServer.targetItemJson(3, cancelled = 5_000))),
            )
            val body = """{"fileId":"$fileId","seqs":[1,3],"reasonCode":"INSTITUTION_REQUESTED","evidenceRef":"INQ-DEMO-0001","reason":"demo step-up waiver check"}"""
            val id = idOf(operator.perform(post("/api/approvals/acq-reacquisition").contentType(MediaType.APPLICATION_JSON).content(body)))
            acquiringReplies[id] = { StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.decisionJson(it, "EXECUTED", resultFileId = fileId.removeSuffix("001") + "002")) }
            val res = approveWithoutCode(quick("aq-approver"), id)
            (res.status to json.readTree(res.contentAsByteArray).get("status").asString()) shouldBe (200 to "EXECUTED")
            skipped(acquiringApprover.account.id.value) shouldBe listOf(executingCorrelation(id))
            failures(acquiringApprover.account.id.value) shouldBe 0
        }
    }
})
