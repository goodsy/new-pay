package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RecoverOwnerExecutionsUseCase
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * 가맹점 계약 승인 화면 HTTP 흐름(MERCHANT-OPS-002 §8.2 B2-2-11 — 실제 2단계 로그인 세션, 실제 MySQL, MASTER 실제 HTTP 대역): OPERATOR 요청 → APPROVER TOTP 승인 → EXECUTED,
 * **응답 유실**(대역이 첫 실행 요청에 read timeout까지 응답하지 않음 → EXECUTION_UNKNOWN → 회복 job 재전송 → 대역이 받은 두 본문 바이트 동일·서명/`t`만 다름 → EXECUTED — stub이라
 * MASTER 효과는 단언하지 않는다), 등록자 403, CSRF 없는 POST 거부, VIEWER 403, 상세 `pendingApprovals`(C-24).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantApprovalFlowHttpSpec(
    private val mockMvc: MockMvc,
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    recovery: RecoverOwnerExecutionsUseCase,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    storage: AdminAccountStorage,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val f = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    // B3-1F: 개통 승인 실행 경로까지 연결한다(install이 base[f].install을 포함 — 계약 승인 행렬은 그대로).
    val act = MerchantActivationApprovalFixture(f, merchants, path, storage)
    act.install()
    afterSpec { act.uninstall() }
    val json = JsonMapper.builder().build()
    fun root() = AdminDbAccounts.root(container)
    fun withRoles(vararg roles: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, *roles) }
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun body(m: String, evidence: String = MerchantOpsApprovalFixture.EVIDENCE) =
        """{"merchantId":"$m","contractId":"${f.contractOf(m)}","revisionNo":2,"evidenceRef":"$evidence"}"""
    fun request(client: AdminHttpClient, m: String, xsrf: Boolean = true) =
        client.perform(post("/api/approvals/merchant-contract-approval").contentType(MediaType.APPLICATION_JSON).content(body(m)), xsrf)
    fun approve(client: AdminHttpClient, approver: LoginReadyAccounts.LoginReady, id: String) = client.perform(
        post("/api/approvals/$id/approve").contentType(MediaType.APPLICATION_JSON)
            .content(AdminHttpClient.json("totpCode" to TestTotp.code(approver.secret, Instant.now().plusSeconds(30)))),
    )
    fun status(id: String) = root().use { it.scalar("SELECT CONCAT(status, '|', COALESCE(result_code, '-')) FROM admin_approval_request WHERE approval_id = ?", id) as String }

    Given("FH-1 OPERATOR request → APPROVER TOTP approval → EXECUTED (screen API)") {
        Then("201 approvalId; the merchant detail shows it pending; CSRF-less POST and a VIEWER are refused; approve 200 EXECUTED; the detail list then has no pending approval") {
            val m = f.newMerchantId().also { f.detail(it) }
            val operator = loggedIn(withRoles(AdminRole.OPERATOR))
            request(operator, m, xsrf = false).status shouldBe 403
            request(loggedIn(withRoles(AdminRole.VIEWER)), m).status shouldBe 403
            val created = request(operator, m)
            created.status shouldBe 201
            val id = json.readTree(created.contentAsByteArray).get("approvalId").asString()
            val detail = json.readTree(operator.perform(get("/api/merchants/$m")).contentAsByteArray)
            // 기대값 변경(B2-2 Demo 개선 C-2): 승인 대기 표시에 대상 Revision 번호(계약 승인만 — 첫 MID 발급은 null).
            detail.get("pendingApprovals").toString() shouldBe """[{"approvalId":"$id","kind":"MERCHANT_CONTRACT_APPROVAL","status":"PENDING","revisionNo":2}]"""
            detail.get("contracts").get(0).get("revisions").get(1).get("registeredBy").asString() shouldBe StubMasterOpsServer.REGISTRANT
            val approval = operator.perform(get("/api/approvals/$id"))
            approval.contentAsString shouldNotContain MerchantOpsApprovalFixture.EVIDENCE
            json.readTree(approval.contentAsByteArray).get("merchant").get("pinnedTarget").get("registeredBy").asString() shouldBe StubMasterOpsServer.REGISTRANT
            f.executeReplies[id] = f.succeeded(f.contractOf(m) + "#2")
            val approver = withRoles(AdminRole.APPROVER)
            val res = approve(loggedIn(approver), approver, id)
            (res.status to json.readTree(res.contentAsByteArray).get("status").asString()) shouldBe (200 to "EXECUTED")
            json.readTree(operator.perform(get("/api/merchants/$m")).contentAsByteArray).get("pendingApprovals").size() shouldBe 0
        }
        Then("the registrant (OPERATOR + APPROVER) approving gets 403 APPROVER_IS_REGISTRANT and the approval stays PENDING") {
            val registrant = withRoles(AdminRole.OPERATOR, AdminRole.APPROVER)
            val m = f.newMerchantId().also { f.detail(it, registeredBy = "admin:${registrant.account.id.value}") }
            val id = json.readTree(request(loggedIn(withRoles(AdminRole.OPERATOR)), m).contentAsByteArray).get("approvalId").asString()
            approve(loggedIn(registrant), registrant, id).let { it.status to it.contentAsString } shouldBe (403 to """{"error":"APPROVER_IS_REGISTRANT"}""")
            status(id) shouldBe "PENDING|-"
        }
    }

    Given("FH-4 B3-1F (D-MOPS-009 ③) payment method activation approval with real login sessions") {
        Then("the activation registrant (OPERATOR + APPROVER) approving gets 403 {\"error\":\"APPROVER_IS_REGISTRANT\"} without values, PENDING; another APPROVER → EXECUTED") {
            val registrant = withRoles(AdminRole.OPERATOR, AdminRole.APPROVER)
            val m = f.newMerchantId().also { act.detail(it, activationRegistrant = "admin:${registrant.account.id.value}") }
            val body = """{"merchantId":"$m","merchantAccountId":"${act.midOf(m)}","activationId":"${act.activationOf(m)}","evidenceRef":"${MerchantActivationApprovalFixture.ACTIVATION_EVIDENCE}"}"""
            val created = loggedIn(withRoles(AdminRole.OPERATOR)).perform(post("/api/approvals/merchant-payment-method-activation").contentType(MediaType.APPLICATION_JSON).content(body))
            created.status shouldBe 201
            val id = json.readTree(created.contentAsByteArray).get("approvalId").asString()
            approve(loggedIn(registrant), registrant, id).let { it.status to it.contentAsString } shouldBe (403 to """{"error":"APPROVER_IS_REGISTRANT"}""")
            status(id) shouldBe "PENDING|-"
            f.executeReplies[id] = f.succeeded(act.activationOf(m))
            val other = withRoles(AdminRole.APPROVER)
            val res = approve(loggedIn(other), other, id)
            (res.status to json.readTree(res.contentAsByteArray).get("status").asString()) shouldBe (200 to "EXECUTED")
        }
    }

    Given("FH-3 B2-3 first MID screen API") {
        Then("POST /api/approvals/merchant-first-mid: OPERATOR 201, VIEWER 403, CSRF-less 403; the pending list names the kind; APPROVER approves → EXECUTED") {
            val m = f.newMerchantId().also { f.firstMidDetail(it) }
            val body = """{"merchantId":"$m","contractId":"${f.contractOf(m)}","agreementId":"${f.agreementOf(m)}","revisionNo":1,"primarySiteId":"${f.siteOf(m)}","evidenceRef":"MID-1"}"""
            fun send(client: AdminHttpClient, xsrf: Boolean = true) =
                client.perform(post("/api/approvals/merchant-first-mid").contentType(MediaType.APPLICATION_JSON).content(body), xsrf)
            val operator = loggedIn(withRoles(AdminRole.OPERATOR))
            send(operator, xsrf = false).status shouldBe 403
            send(loggedIn(withRoles(AdminRole.VIEWER))).status shouldBe 403
            val created = send(operator)
            created.status shouldBe 201
            val id = json.readTree(created.contentAsByteArray).get("approvalId").asString()
            val pending = json.readTree(operator.perform(get("/api/merchants/$m")).contentAsByteArray).get("pendingApprovals").get(0)
            listOf(pending.get("kind").asString(), pending.get("revisionNo").isNull.toString()) shouldBe listOf("MERCHANT_FIRST_MID_ISSUANCE", "true")
            f.executeReplies[id] = f.succeeded(f.midOf(m))
            val approver = withRoles(AdminRole.APPROVER)
            json.readTree(approve(loggedIn(approver), approver, id).contentAsByteArray).get("status").asString() shouldBe "EXECUTED"
            // L-13(Demo 개선 C-3): 감사 화면 API가 계정이 아닌 대상(가맹점)을 targetRef로 내보낸다.
            val audit = json.readTree(loggedIn(ready.bootstrapped(AdminRole.SECURITY_ADMIN)).perform(get("/api/audit?action=APPROVAL_EXECUTED")).contentAsByteArray).get("entries")
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            (0 until audit.size()).map { audit.get(it) }.single { it.get("approvalId").asString() == id }.get("targetRef").asString() shouldBe MerchantOpsApprovalFixture.DEFAULT_CODE
            // MERCHANT-CODE-001 MC-3-16: 같은 행의 내부 키(targetMerchantId = mer_ — 응답 포함·화면 미표시).
            (0 until audit.size()).map { audit.get(it) }.single { it.get("approvalId").asString() == id }.get("targetMerchantId").asString() shouldBe m
        }
    }

    Given("FH-2 lost response (stub: no answer within the read timeout) → EXECUTION_UNKNOWN → recovery resend of the same stored bytes → EXECUTED") {
        Then("the two execution requests have identical bodies (key, correlation, fields), only the signature/t differs; the result converges") {
            val m = f.newMerchantId().also { f.detail(it) }
            val operator = loggedIn(withRoles(AdminRole.OPERATOR))
            val id = json.readTree(request(operator, m).contentAsByteArray).get("approvalId").asString()
            var first = true
            f.executeReplies[id] = {
                if (first) {
                    first = false
                    Reply(200, StubMasterOpsServer.succeeded(f.contractOf(m) + "#2"), delayMillis = 2_600)
                } else {
                    Reply(200, StubMasterOpsServer.succeeded(f.contractOf(m) + "#2", replayed = true))
                }
            }
            val approver = withRoles(AdminRole.APPROVER)
            val res = approve(loggedIn(approver), approver, id)
            (res.status to json.readTree(res.contentAsByteArray).get("status").asString()) shouldBe (200 to "EXECUTION_UNKNOWN")
            root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.000' WHERE approval_id = ?", id) }
            Thread.sleep(1_200)
            recovery.recoverDue()
            status(id) shouldBe "EXECUTED|-"
            val sent = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CONTRACT_REVISION_APPROVALS).filter { f.idOf(it.body) == id }
            sent.size shouldBe 2
            (sent[0].body.contentEquals(sent[1].body) to (sent[0].signature != sent[1].signature)) shouldBe (true to true)
            sent.all { it.signatureValid } shouldBe true
            root().use {
                it.column("SELECT action FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id)
            } shouldBe listOf("APPROVAL_REQUESTED", "APPROVAL_EXECUTING", "APPROVAL_EXECUTION_UNKNOWN", "APPROVAL_EXECUTED")
        }
    }
})
