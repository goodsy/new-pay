package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubFinancialOpsServer
import com.paycore.admin.support.StubFinancialOpsServer.Reply
import com.paycore.admin.support.column
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

/**
 * 재매입 화면 HTTP(ACQ-ADMIN-001 B1-A §5 — 실제 2단계 로그인 세션, 실제 MySQL 감사 행, FINANCIAL 실제 HTTP 대역): 미리 보기 `POST /api/acquisition-files/{fileId}/reacquisition-targets`
 * (조회 4역할, 조회 키, 상세와 같은 감사), 요청 `POST /api/approvals/acq-reacquisition`(OPERATOR만 — D-ACQA-002), 상세 `GET /api/approvals/{id}`의 `acquiring`(사유 문구·근거 없음),
 * CSRF 없는 POST 거부.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class AcquiringOpsApprovalHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubFinancialOpsServer.start()
    val json = JsonMapper.builder().build()
    val fileId = "ACQ.H${System.nanoTime() % 100_000_000L}.TEST.20261105.001"
    StubFinancialOpsServer.reply(
        StubFinancialOpsServer.targetsPath(fileId),
        Reply(200, StubFinancialOpsServer.targetsJson(fileId, StubFinancialOpsServer.targetItemJson(1), StubFinancialOpsServer.targetItemJson(3, cancelled = 5_000))),
    )
    fun elements(array: tools.jackson.databind.JsonNode) = (0 until array.size()).map { array.get(it) }
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun preview(client: AdminHttpClient, body: String = """{"seqs":[1,3]}""", xsrf: Boolean = true) =
        client.perform(post("/api/acquisition-files/$fileId/reacquisition-targets").contentType(MediaType.APPLICATION_JSON).content(body), xsrf)
    val requestBody = """{"fileId":"$fileId","seqs":[1,3],"reasonCode":"INSTITUTION_REQUESTED","evidenceRef":"INQ-HTTP-0001","reason":"secret operator reason text"}"""
    fun request(client: AdminHttpClient, xsrf: Boolean = true) =
        client.perform(post("/api/approvals/acq-reacquisition").contentType(MediaType.APPLICATION_JSON).content(requestBody), xsrf)

    Given("HT-1 the screen preview (read key, audited like the file detail)") {
        Then("a VIEWER gets FINANCIAL-computed items with an ACQUISITION_FILE_VIEWED audit row; a malformed body is 400; no role is 403 without a FINANCIAL call") {
            val viewer = withRole(AdminRole.VIEWER)
            val client = loggedIn(viewer)
            val ok = preview(client)
            ok.status shouldBe 200
            elements(json.readTree(ok.contentAsByteArray).get("items")).map { it.get("seq").asInt() to it.get("expectedNetMinor").asLong() } shouldBe listOf(1 to 10_000L, 3 to 5_000L)
            AdminDbAccounts.root(container).use {
                it.column("SELECT CONCAT(action, '|', COALESCE(target_ref, '-')) FROM admin_audit_event WHERE actor_id = ? ORDER BY event_id DESC LIMIT 1", viewer.account.id.value)
            } shouldBe listOf("ACQUISITION_FILE_VIEWED|$fileId")
            listOf(preview(client, """{"seqs":"1"}""").status, preview(client, """{"seqs":[3,1]}""").status) shouldBe listOf(400, 400)
            val before = StubFinancialOpsServer.received.size
            preview(loggedIn(ready.withoutRoles())).status shouldBe 403
            StubFinancialOpsServer.received.size shouldBe before
        }
    }

    Given("HT-2 the request endpoint and the approval detail") {
        // 기대값 변경 ②(rev 0.6) 근거: 사용자 결정 D-ACQA-005(Q-ACQA-09, 2026-11-07) — 재매입 근거 참조는 D-OPS-010과 같이 APPROVER·요청자 본인만 별도 조회(HT-3)로 보고 조회마다 감사,
        // 그 밖 역할·목록에는 숨긴다. 그래서 상세 4역할 공통 응답(`acquiring`)에서 근거 참조를 뺀다(rev 0.5 I-24 "상세에 근거 표시"를 대체). 사유 문구는 계속 숨긴다(I-4).
        Then("an OPERATOR creates the request (201); a VIEWER is 403; no CSRF token is refused; the detail shows the pinned records and reason code but neither evidence reference nor reason text") {
            val operator = loggedIn(withRole(AdminRole.OPERATOR))
            request(operator, xsrf = false).status shouldBe 403
            request(loggedIn(withRole(AdminRole.VIEWER))).status shouldBe 403
            val created = request(operator)
            created.status shouldBe 201
            val id = json.readTree(created.contentAsByteArray).get("approvalId").asString()
            val detail = operator.perform(get("/api/approvals/$id"))
            detail.status shouldBe 200
            val body = json.readTree(detail.contentAsByteArray)
            listOf(
                body.get("kind").asString(), body.get("targetKind").asString(), body.get("targetRef").asString(), body.get("acquiring").get("reasonCode").asString(),
                body.get("acquiring").has("evidenceRef").toString(),
            ) shouldBe listOf("ACQ_REACQUISITION", "ACQUISITION_FILE", fileId, "INSTITUTION_REQUESTED", "false")
            elements(body.get("acquiring").get("pinnedTarget").get("items")).map { it.get("seq").asInt() } shouldBe listOf(1, 3)
            detail.contentAsString shouldNotContain "INQ-HTTP-0001"
            detail.contentAsString shouldNotContain "secret operator reason text"
            // 다른 조회 역할(VIEWER)의 상세에도 근거 참조가 없다(D-ACQA-005 — 상세는 4역할 공통).
            loggedIn(withRole(AdminRole.VIEWER)).perform(get("/api/approvals/$id")).let { other ->
                other.status shouldBe 200
                other.contentAsString shouldNotContain "INQ-HTTP-0001"
            }
            operator.perform(get("/api/approvals")).let { list ->
                list.status shouldBe 200
                list.contentAsString shouldNotContain "INQ-HTTP-0001"
                list.contentAsString shouldNotContain "secret operator reason text"
            }
            request(operator).status shouldBe 409
        }
    }

    Given("HT-3 D-ACQA-005: the evidence reference of a re-acquisition approval is a separate authorized lookup (same endpoint, checks and audit as D-OPS-010)") {
        val otherFile = "ACQ.E${System.nanoTime() % 100_000_000L}.TEST.20261105.001"
        StubFinancialOpsServer.reply(
            StubFinancialOpsServer.targetsPath(otherFile),
            Reply(200, StubFinancialOpsServer.targetsJson(otherFile, StubFinancialOpsServer.targetItemJson(1))),
        )
        val requesterAccount = withRole(AdminRole.OPERATOR)
        val requester = loggedIn(requesterAccount)
        val created = requester.perform(
            post("/api/approvals/acq-reacquisition").contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileId":"$otherFile","seqs":[1],"reasonCode":"INSTITUTION_REQUESTED","evidenceRef":"INQ-EVD-0005","reason":"secret evidence reason 0005"}"""),
        )
        created.status shouldBe 201
        val id = json.readTree(created.contentAsByteArray).get("approvalId").asString()
        val url = "/api/approvals/$id/justification"
        fun audits() = AdminDbAccounts.root(container).use {
            it.column(
                "SELECT CONCAT(result_code, '|', actor_id, '|', COALESCE(target_ref, '-'), '|', COALESCE(detail, '-')) FROM admin_audit_event " +
                    "WHERE approval_id = ? AND action = 'APPROVAL_JUSTIFICATION_VIEWED' ORDER BY event_id",
                id,
            )
        }

        Then("a VIEWER, another OPERATOR (not the requester) and a SECURITY_ADMIN get 403 FORBIDDEN without the reference; each refused attempt is audited (no text)") {
            listOf(withRole(AdminRole.VIEWER), withRole(AdminRole.OPERATOR), ready.bootstrapped(AdminRole.SECURITY_ADMIN)).forEach { account ->
                loggedIn(account).perform(get(url)).let { it.status to it.contentAsString } shouldBe (403 to """{"error":"FORBIDDEN"}""")
            }
            audits().map { it.substringBefore('|') } shouldBe listOf("FORBIDDEN", "FORBIDDEN", "FORBIDDEN")
        }

        Then("the requester and an APPROVER read it (200, no-store, reference only — never the reason text); each read adds exactly one OK audit row with the original file as target") {
            val before = audits().size
            requester.perform(get(url)).let { listOf(it.status, it.contentAsString, it.getHeader("Cache-Control").orEmpty().contains("no-store")) } shouldBe
                listOf(200, """{"approvalId":"$id","evidenceRef":"INQ-EVD-0005"}""", true)
            audits().drop(before) shouldBe listOf("OK|${requesterAccount.account.id.value}|$otherFile|-")
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            loggedIn(approver).perform(get(url)).let { it.status to it.contentAsString } shouldBe (200 to """{"approvalId":"$id","evidenceRef":"INQ-EVD-0005"}""")
            audits().drop(before) shouldBe listOf("OK|${requesterAccount.account.id.value}|$otherFile|-", "OK|${approver.account.id.value}|$otherFile|-")
            audits().forEach {
                it shouldNotContain "INQ-EVD-0005"
                it shouldNotContain "secret evidence reason 0005"
            }
        }

        Then("the general detail and the list never carry the reference for any role (the requester included)") {
            listOf(requester.perform(get("/api/approvals/$id")), requester.perform(get("/api/approvals?view=mine"))).forEach {
                it.status shouldBe 200
                it.contentAsString shouldNotContain "INQ-EVD-0005"
                it.contentAsString shouldNotContain "secret evidence reason 0005"
            }
            json.readTree(requester.perform(get("/api/approvals/$id")).contentAsByteArray).get("acquiring").has("evidenceRef") shouldBe false
        }
    }
})
