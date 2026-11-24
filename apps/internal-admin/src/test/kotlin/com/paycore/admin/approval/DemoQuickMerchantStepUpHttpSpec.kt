package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.security.DemoQuickLoginTestConfig
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.SwitchableAuditStoreConfig
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper

/**
 * 결정 ③의 2-TX 경로(ADMIN-DEMO-LOGIN-001 리뷰 MINOR-A — MerchantOpsApprovalService, 가맹점 계약 승인). 데모 간편 로그인 승인자가 TOTP 없이:
 * - 등록자면 재인증 자리 **전에** 403 `APPROVER_IS_REGISTRANT`(PENDING, 생략 감사 없음 — 규칙 순서 불변),
 * - 등록자가 아니면 EXECUTED + `STEP_UP_SKIPPED` 감사(TX1의 `APPROVAL_EXECUTING`과 같은 상관 ID).
 * MASTER는 실제 HTTP 대역(StubMasterOpsServer — MerchantOpsApprovalFixture). 컨텍스트는 DemoQuick* spec과 같은 설정(공유).
 */
@SpringBootTest(properties = ["paycore.admin.demo-quick-login.enabled=true", DemoQuickLoginTestConfig.ACCOUNTS])
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class, SwitchableAuditStoreConfig::class)
class DemoQuickMerchantStepUpHttpSpec(
    private val mockMvc: MockMvc,
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    storage: AdminAccountStorage,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val f = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    f.install()
    afterSpec { f.uninstall() }
    val json = JsonMapper.builder().build()
    fun root() = AdminDbAccounts.root(container)
    val registrant = ready.withoutRoles(login = "mo-registrant").also { path.grant(it.account.id, AdminRole.OPERATOR, AdminRole.APPROVER) }
    val approver = ready.withoutRoles(login = "mo-approver").also { path.grant(it.account.id, AdminRole.APPROVER) }
    val operator = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR) }
    val operatorClient = AdminHttpClient(mockMvc).also { it.login(operator.loginName, operator.password, operator.secret).status shouldBe 200 }

    fun quick(loginName: String) = AdminHttpClient(mockMvc).also {
        it.csrf()
        it.perform(post("/api/auth/demo-quick-login").contentType(MediaType.APPLICATION_JSON).content(AdminHttpClient.json("loginName" to loginName))).status shouldBe 200
    }
    fun request(m: String): String {
        val body = """{"merchantId":"$m","contractId":"${f.contractOf(m)}","revisionNo":2,"evidenceRef":"${MerchantOpsApprovalFixture.EVIDENCE}"}"""
        val created = operatorClient.perform(post("/api/approvals/merchant-contract-approval").contentType(MediaType.APPLICATION_JSON).content(body))
        created.status shouldBe 201
        return json.readTree(created.contentAsByteArray).get("approvalId").asString()
    }
    fun approveWithoutCode(c: AdminHttpClient, id: String) =
        c.perform(post("/api/approvals/$id/approve").contentType(MediaType.APPLICATION_JSON).content(AdminHttpClient.json("totpCode" to "")))
    fun skipped(actorId: String): List<String> = root().use {
        it.column("SELECT correlation_id FROM admin_audit_event WHERE actor_id = ? AND action = 'STEP_UP_SKIPPED' ORDER BY event_id", actorId)
    }
    fun status(id: String) = root().use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) as String }

    Given("a merchant contract approval decided from a demo quick login session") {
        Then("the registrant is refused before the step-up (403 APPROVER_IS_REGISTRANT, PENDING, no skip audit)") {
            val m = f.newMerchantId().also { f.detail(it, registeredBy = "admin:${registrant.account.id.value}") }
            val id = request(m)
            approveWithoutCode(quick("mo-registrant"), id).let { it.status to it.contentAsString } shouldBe (403 to """{"error":"APPROVER_IS_REGISTRANT"}""")
            status(id) shouldBe "PENDING"
            skipped(registrant.account.id.value) shouldBe emptyList()
        }
        Then("another APPROVER executes it without a TOTP (200 EXECUTED); the skip audit shares the correlation of APPROVAL_EXECUTING; failure count unchanged") {
            val m = f.newMerchantId().also { f.detail(it) }
            val id = request(m)
            f.executeReplies[id] = f.succeeded(f.contractOf(m) + "#2")
            val res = approveWithoutCode(quick("mo-approver"), id)
            (res.status to json.readTree(res.contentAsByteArray).get("status").asString()) shouldBe (200 to "EXECUTED")
            val executing = root().use {
                it.scalar("SELECT correlation_id FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_EXECUTING'", id) as String
            }
            skipped(approver.account.id.value) shouldBe listOf(executing)
            root().use { (it.scalar("SELECT failed_attempts FROM admin_account WHERE actor_id = ?", approver.account.id.value) as Number).toInt() } shouldBe 0
        }
    }
})
