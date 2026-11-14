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
 * B3-1 화면 HTTP 흐름(MERCHANT-OPS-002 §8.3 B3-1 8 — 실제 2단계 로그인 세션·MySQL·MASTER 실제 HTTP 대역): 개통 승인 요청(OPERATOR, CSRF) → 상세 `pendingApprovals`·새 MASTER 필드
 * 통과 → APPROVER TOTP 승인 중 **응답 유실**(대역이 read timeout까지 응답하지 않음) → EXECUTION_UNKNOWN → 회복 재전송(같은 저장 바이트·서명만 다름) → EXECUTED.
 * Site 심사 승인 요청 화면 API(VIEWER 403·CSRF 없는 POST 403). stub이라 MASTER 효과는 단언하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantActivationFlowHttpSpec(
    private val mockMvc: MockMvc,
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    recovery: RecoverOwnerExecutionsUseCase,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    storage: AdminAccountStorage,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val base = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    val f = MerchantActivationApprovalFixture(base, merchants, path, storage)
    f.install()
    afterSpec { f.uninstall() }
    val json = JsonMapper.builder().build()
    fun root() = AdminDbAccounts.root(container)
    fun withRoles(vararg roles: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, *roles) }
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun approve(client: AdminHttpClient, approver: LoginReadyAccounts.LoginReady, id: String) = client.perform(
        post("/api/approvals/$id/approve").contentType(MediaType.APPLICATION_JSON)
            .content(AdminHttpClient.json("totpCode" to TestTotp.code(approver.secret, Instant.now().plusSeconds(30)))),
    )
    fun status(id: String) = root().use { it.scalar("SELECT CONCAT(status, '|', COALESCE(result_code, '-')) FROM admin_approval_request WHERE approval_id = ?", id) as String }

    Given("FA-1 activation approval over HTTP with a lost response") {
        Then("201 → detail shows the pending kind and the new MASTER fields → approve times out (EXECUTION_UNKNOWN) → recovery resends the same bytes → EXECUTED") {
            val m = base.newMerchantId().also { f.detail(it) }
            val body = """{"merchantId":"$m","merchantAccountId":"${f.midOf(m)}","activationId":"${f.activationOf(m)}","evidenceRef":"${MerchantActivationApprovalFixture.ACTIVATION_EVIDENCE}"}"""
            fun send(client: AdminHttpClient, xsrf: Boolean = true) =
                client.perform(post("/api/approvals/merchant-payment-method-activation").contentType(MediaType.APPLICATION_JSON).content(body), xsrf)
            val operator = loggedIn(withRoles(AdminRole.OPERATOR))
            send(operator, xsrf = false).status shouldBe 403
            send(loggedIn(withRoles(AdminRole.VIEWER))).status shouldBe 403
            val created = send(operator)
            created.status shouldBe 201
            val id = json.readTree(created.contentAsByteArray).get("approvalId").asString()
            val detail = json.readTree(operator.perform(get("/api/merchants/$m")).contentAsByteArray)
            detail.get("pendingApprovals").toString() shouldBe """[{"approvalId":"$id","kind":"MERCHANT_PAYMENT_METHOD_ACTIVATION","status":"PENDING","revisionNo":null}]"""
            val account = detail.get("accounts").get(0)
            listOf(account.get("paymentMethods").get(0).get("registeredBy").asString(), account.get("siteConfirmations").size().toString(), detail.get("sites").get(1).get("registeredBy").asString()) shouldBe
                listOf(StubMasterOpsServer.SITE_REGISTRANT, "0", StubMasterOpsServer.SITE_REGISTRANT)
            val approval = operator.perform(get("/api/approvals/$id"))
            approval.contentAsString shouldNotContain MerchantActivationApprovalFixture.ACTIVATION_EVIDENCE
            json.readTree(approval.contentAsByteArray).get("merchant").get("pinnedTarget").get("registeredBy").asString() shouldBe StubMasterOpsServer.SITE_REGISTRANT
            var first = true
            base.executeReplies[id] = {
                if (first) {
                    first = false
                    Reply(200, StubMasterOpsServer.succeeded(f.activationOf(m)), delayMillis = 2_600)
                } else {
                    Reply(200, StubMasterOpsServer.succeeded(f.activationOf(m), replayed = true))
                }
            }
            val approver = withRoles(AdminRole.APPROVER)
            val res = approve(loggedIn(approver), approver, id)
            (res.status to json.readTree(res.contentAsByteArray).get("status").asString()) shouldBe (200 to "EXECUTION_UNKNOWN")
            root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.000' WHERE approval_id = ?", id) }
            Thread.sleep(1_200)
            recovery.recoverDue()
            status(id) shouldBe "EXECUTED|-"
            val sent = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS).filter { base.idOf(it.body) == id }
            sent.size shouldBe 2
            (sent[0].body.contentEquals(sent[1].body) to (sent[0].signature != sent[1].signature)) shouldBe (true to true)
            sent.all { it.signatureValid } shouldBe true
            root().use { it.column("SELECT action FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id) } shouldBe
                listOf("APPROVAL_REQUESTED", "APPROVAL_EXECUTING", "APPROVAL_EXECUTION_UNKNOWN", "APPROVAL_EXECUTED")
        }
    }

    Given("FS-1 site review approval screen API") {
        Then("OPERATOR 201 (pending kind MERCHANT_SITE_REVIEW_APPROVAL), VIEWER 403, CSRF-less 403; a reject reason code is 400 reasonCode") {
            val m = base.newMerchantId().also { f.detail(it) }
            fun send(client: AdminHttpClient, reason: String = "REVIEW_PASSED", xsrf: Boolean = true) = client.perform(
                post("/api/approvals/merchant-site-review-approval").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"merchantId":"$m","merchantSiteId":"${f.secondSiteOf(m)}","reasonCode":"$reason","evidenceRef":"PG-REVIEW/1"}"""),
                xsrf,
            )
            val operator = loggedIn(withRoles(AdminRole.OPERATOR))
            send(operator, xsrf = false).status shouldBe 403
            send(loggedIn(withRoles(AdminRole.VIEWER))).status shouldBe 403
            send(operator, reason = "DOCUMENT_INSUFFICIENT").let { it.status to json.readTree(it.contentAsByteArray).get("field").asString() } shouldBe (400 to "reasonCode")
            send(operator).status shouldBe 201
            json.readTree(operator.perform(get("/api/merchants/$m")).contentAsByteArray).get("pendingApprovals").get(0).get("kind").asString() shouldBe "MERCHANT_SITE_REVIEW_APPROVAL"
        }
    }

    Given("FS-2 B3-2 currency enablement request screen API (§8.4 B3-2-5)") {
        Then("OPERATOR 201 (pending kind MERCHANT_CURRENCY_ENABLEMENT), VIEWER 403, CSRF-less 403; DATE without a date is 400 effectiveFromDate; USD is 400 currency") {
            val cf = MerchantCurrencyApprovalFixture(base, merchants, path, storage)
            val m = base.newMerchantId().also { cf.detail(it) }
            fun send(client: AdminHttpClient, extra: String = """"effectiveFromMode":"AT_EXECUTION"""", currency: String = "KRW", xsrf: Boolean = true) = client.perform(
                post("/api/approvals/merchant-currency-enablement").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"merchantId":"$m","merchantAccountId":"${cf.midOf(m)}","currency":"$currency",$extra,"evidenceRef":"VAN-REPLY/1"}"""),
                xsrf,
            )
            val operator = loggedIn(withRoles(AdminRole.OPERATOR))
            send(operator, xsrf = false).status shouldBe 403
            send(loggedIn(withRoles(AdminRole.VIEWER))).status shouldBe 403
            send(operator, extra = """"effectiveFromMode":"DATE"""").let { it.status to json.readTree(it.contentAsByteArray).get("field").asString() } shouldBe (400 to "effectiveFromDate")
            send(operator, currency = "USD").let { it.status to json.readTree(it.contentAsByteArray).get("field").asString() } shouldBe (400 to "currency")
            send(operator).status shouldBe 201
            json.readTree(operator.perform(get("/api/merchants/$m")).contentAsByteArray).get("pendingApprovals").get(0).get("kind").asString() shouldBe "MERCHANT_CURRENCY_ENABLEMENT"
        }
    }
})
