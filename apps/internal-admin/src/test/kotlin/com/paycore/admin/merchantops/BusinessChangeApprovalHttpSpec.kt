package com.paycore.admin.merchantops

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.approval.BusinessChangeApprovalFixture
import com.paycore.admin.approval.MerchantOpsApprovalFixture
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.merchantops.application.provided.BusinessChangeRequests
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import tools.jackson.databind.json.JsonMapper

/**
 * BIZ-CRIT-APPROVAL-001 S3 화면 API(§5.2 — 실제 보안·MVC·MySQL, MASTER 대역): 승인 요청 201, 1인 수정·등록 완료는 대기 중 409 `BUSINESS_CHANGE_PENDING`(MASTER 미호출), MASTER 2인 필요·차단
 * 저장 거절은 409로 전달, 1인 수정 선택 `evidenceRef`는 MASTER로 전달, 승인은 `revalidated` 필수, 원문 프록시 경로, 1인 근거 원문 조회(APPROVER·저장 요청자만 — MASTER 저장 행위자로 판정),
 * 사업자 상세 advisory(`criticalChangeMode`·`subjectCorrection`)와 `pendingCriticalChange`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class BusinessChangeApprovalHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    requests: BusinessChangeRequests,
    storage: AdminAccountStorage,
) : BehaviorSpec({

    val base = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    val f = BusinessChangeApprovalFixture(base, requests, owners, path, storage)
    f.install()
    afterSpec { f.uninstall() }
    val json = JsonMapper.builder().build()
    var subSeq = System.nanoTime()
    fun sub() = "%032x".format(++subSeq)
    val masterBase = "/internal/v1/admin/businesses"
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun send(client: AdminHttpClient, builder: MockHttpServletRequestBuilder, body: String? = null) =
        client.perform(if (body == null) builder else builder.contentType(MediaType.APPLICATION_JSON).content(body))
    fun changeBody(reason: String? = BusinessChangeApprovalFixture.REASON, evidence: String? = BusinessChangeApprovalFixture.EVIDENCE) =
        BusinessInputFixture.json(f.content(), linkedMapOf("submissionId" to sub(), "version" to 0L, "reason" to reason), if (evidence == null) "" else ",\"evidenceRef\":\"$evidence\"")
    fun masterUpdates(b: String) = StubMasterOpsServer.requestsForPath("$masterBase/commands/update").count { json.readTree(it.body).get("businessId").asString() == b }

    Given("§5.2 critical change request and the 1-person path while pending") {
        Then("201 {approvalId}; then PUT update → 409 BUSINESS_CHANGE_PENDING without MASTER; MASTER APPROVAL_REQUIRED on a later 1-person update → 409 with the code") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val b = f.newBusinessId().also { f.state(it) }
            val created = send(client, post("/api/businesses/$b/critical-change-requests"), changeBody())
            created.status shouldBe 201
            val approvalId = json.readTree(created.contentAsString).get("approvalId").asString()
            approvalId.matches(Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")) shouldBe true
            val pendingPut = send(client, put("/api/businesses/$b"), BusinessInputFixture.changeJson(sub(), version = 0, reason = "정정"))
            listOf(pendingPut.status, pendingPut.contentAsString) shouldBe listOf(409, """{"error":"BUSINESS_CHANGE_PENDING"}""")
            masterUpdates(b) shouldBe 0
            // 대기 없는 다른 사업자: MASTER가 2인 필요를 저장 거절 → 409로 전달.
            val other = f.newBusinessId()
            StubMasterOpsServer.reply("$masterBase/commands/update", Reply(200, StubMasterOpsServer.rejected("BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED")))
            val required = send(client, put("/api/businesses/$other"), BusinessInputFixture.changeJson(sub(), version = 0, reason = "정정"))
            listOf(required.status, required.contentAsString) shouldBe listOf(409, """{"error":"BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED"}""")
        }

        Then("1-person update with an optional evidenceRef sends it to MASTER (key only when present); a card-number-shaped evidenceRef is 400 before MASTER") {
            val client = loggedIn(withRole(AdminRole.OPERATOR))
            val b = f.newBusinessId()
            StubMasterOpsServer.reply("$masterBase/commands/update", Reply(200, StubMasterOpsServer.succeeded(b)))
            send(client, put("/api/businesses/$b"), BusinessInputFixture.changeJson(sub(), version = 0, reason = "정정", extra = ",\"evidenceRef\":\"REG-DOC/9\"")).status shouldBe 200
            json.readTree(StubMasterOpsServer.requestsForPath("$masterBase/commands/update").last().body).get("evidenceRef").asString() shouldBe "REG-DOC/9"
            send(client, put("/api/businesses/$b"), BusinessInputFixture.changeJson(sub(), version = 0, reason = "정정")).status shouldBe 200
            json.readTree(StubMasterOpsServer.requestsForPath("$masterBase/commands/update").last().body).has("evidenceRef") shouldBe false
            val bad = send(client, put("/api/businesses/$b"), BusinessInputFixture.changeJson(sub(), version = 0, reason = "정정", extra = ",\"evidenceRef\":\"4111111111111111\""))
            listOf(bad.status, bad.contentAsString) shouldBe listOf(400, """{"error":"INVALID_INPUT","fieldErrors":[{"path":"evidenceRef","code":"CARD_NUMBER_PATTERN"}]}""")
        }
    }

    Given("§5.2 approval and the original proxy over HTTP") {
        Then("approve without revalidated → 400 INVALID_INPUT; with it → EXECUTED; proposal-original afterwards → 409 APPROVAL_NOT_OPEN") {
            val operator = withRole(AdminRole.OPERATOR)
            val b = f.newBusinessId().also { f.state(it) }
            val approvalId = json.readTree(send(loggedIn(operator), post("/api/businesses/$b/critical-change-requests"), changeBody()).contentAsString).get("approvalId").asString()
            f.original(b)
            val approver = base.approver()
            val a = loggedIn(approver)
            val original = send(a, get("/api/approvals/$approvalId/proposal-original"))
            listOf(original.status, json.readTree(original.contentAsString).get("content").get("displayName").asString()) shouldBe listOf(200, BusinessChangeApprovalFixture.NEW_NAME)
            base.executeReplies[approvalId] = f.succeeded(b)
            val noCheck = send(a, post("/api/approvals/$approvalId/approve"), """{"totpCode":"${com.paycore.admin.support.TestTotp.code(approver.secret, java.time.Instant.now().plusSeconds(30))}"}""")
            listOf(noCheck.status, noCheck.contentAsString) shouldBe listOf(400, """{"error":"INVALID_INPUT","field":"revalidated","reason":"REQUIRED"}""")
            val ok = send(a, post("/api/approvals/$approvalId/approve"), """{"totpCode":"${com.paycore.admin.support.TestTotp.code(approver.secret, java.time.Instant.now().plusSeconds(30))}","revalidated":true}""")
            ok.status shouldBe 200
            val closed = send(a, get("/api/approvals/$approvalId/proposal-original"))
            listOf(closed.status, closed.contentAsString) shouldBe listOf(409, """{"error":"APPROVAL_NOT_OPEN"}""")
        }
    }

    Given("1-person evidence lookup and business detail advisory fields") {
        Then("evidence: APPROVER and the recorder 200, another OPERATOR 403 (decided by MASTER's recordedBy); detail carries criticalChangeMode·subjectCorrection·pendingCriticalChange") {
            val recorder = withRole(AdminRole.OPERATOR)
            val b = f.newBusinessId()
            StubMasterOpsServer.reply(
                "$masterBase/$b/business-change-evidence/77",
                Reply(200, """{"historyId":77,"evidenceRef":"REG-DOC/9","recordedBy":"admin:${recorder.account.id.value}","recordedAt":"2026-10-09T02:00:00.000Z"}"""),
            )
            val mine = send(loggedIn(recorder), get("/api/businesses/$b/change-evidence/77"))
            listOf(mine.status, json.readTree(mine.contentAsString).get("evidenceRef").asString()) shouldBe listOf(200, "REG-DOC/9")
            send(loggedIn(withRole(AdminRole.APPROVER)), get("/api/businesses/$b/change-evidence/77")).status shouldBe 200
            send(loggedIn(withRole(AdminRole.OPERATOR)), get("/api/businesses/$b/change-evidence/77")).status shouldBe 403
            // S3 리뷰 ⑩: 조회 권한 없는 역할(VIEWER·SECURITY_ADMIN)은 MASTER 호출 전 403.
            val evidenceReads = StubMasterOpsServer.requestsForPath("$masterBase/$b/business-change-evidence/77").size
            send(loggedIn(withRole(AdminRole.VIEWER)), get("/api/businesses/$b/change-evidence/77")).status shouldBe 403
            send(loggedIn(ready.bootstrapped(AdminRole.SECURITY_ADMIN)), get("/api/businesses/$b/change-evidence/77")).status shouldBe 403
            StubMasterOpsServer.requestsForPath("$masterBase/$b/business-change-evidence/77").size shouldBe evidenceReads

            // 상세: MASTER 상세(REGISTERED, 가맹점 0) + 섹션(계약 없음) → APPROVAL / ALLOWED_WITH_APPROVAL, 대기 요청이 있으면 pendingCriticalChange.
            val (_, pendingBiz) = f.requested()
            val golden = json.readTree(requireNotNull(javaClass.getResourceAsStream("/contracts/business-profile-response-shapes-v2.golden.json")).readAllBytes())
            val detail = json.writeValueAsString(golden.get("detail")).replace(Regex("\"businessId\":\"biz_[0-9A-HJKMNP-TV-Z]{26}\""), "\"businessId\":\"$pendingBiz\"")
                .replace("\"merchantCount\":1", "\"merchantCount\":0")
            StubMasterOpsServer.reply("$masterBase/$pendingBiz", Reply(200, detail))
            StubMasterOpsServer.reply("$masterBase/$pendingBiz/contract-intakes", Reply(200, """{"businessId":"$pendingBiz","judgedAt":null,"businessContract":null,"intakes":[]}"""))
            val viewed = json.readTree(send(loggedIn(withRole(AdminRole.APPROVER)), get("/api/businesses/$pendingBiz")).contentAsString)
            listOf(viewed.get("criticalChangeMode").asString(), viewed.get("subjectCorrection").asString(), viewed.get("pendingCriticalChange").get("status").asString()) shouldBe
                listOf("APPROVAL", "ALLOWED_WITH_APPROVAL", "PENDING")
            viewed.toString() shouldNotContain BusinessChangeApprovalFixture.REASON
        }
    }
})
