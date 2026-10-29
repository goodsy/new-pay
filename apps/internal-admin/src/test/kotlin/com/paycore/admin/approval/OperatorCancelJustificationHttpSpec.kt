package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubPaymentOpsServer
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
 * 근거·사유 별도 권한 조회(ADMIN-PAYMENT-OPS-001 S8, D-OPS-010 — `OperatorCancelJustificationSpec`): `GET /api/approvals/{id}/justification`은 APPROVER 역할과 요청자 본인만
 * 서버에서 허용하고 그 밖은 403. 조회 시도마다 감사 1행(`APPROVAL_JUSTIFICATION_VIEWED` — detail에 문구 없음). 목록·기존 상세(I-98)에는 계속 문구가 없다.
 * 실제 2단계 로그인 세션, 실제 MySQL, PAYMENT는 실제 HTTP 대역.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class OperatorCancelJustificationHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    var seq = System.nanoTime() % 1_000_000L * 100 + 31
    fun paymentId(): String = "pay_01JC" + (++seq).toString().padStart(22, '0')
    fun loggedIn(account: LoginReadyAccounts.LoginReady, at: Instant = Instant.now()) =
        AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret, at).status shouldBe 200 }
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    fun root() = AdminDbAccounts.root(container)
    fun audits(id: String) = root().use {
        it.column(
            "SELECT CONCAT(result_code, '|', actor_id, '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_JUSTIFICATION_VIEWED' " +
                "ORDER BY event_id",
            id,
        )
    }
    val reference = "RECON-2026-0777"
    val reason = "duplicate charge correction 777"

    Given("D-OPS-010: an operator correction cancel request with a purpose reference and a reason") {
        val requesterAccount = withRole(AdminRole.OPERATOR)
        val requester = loggedIn(requesterAccount)
        val pay = paymentId()
        StubPaymentOpsServer.targetFound(pay)
        val created = requester.perform(
            post("/api/approvals/operator-correction-cancel").contentType(MediaType.APPLICATION_JSON).content(
                """{"paymentId":"$pay","amountMinor":7000,"purposeKind":"INCIDENT_OR_RECON_CORRECTION","purposeReference":"$reference","reason":"$reason"}""",
            ),
        )
        created.status shouldBe 201
        val id = Regex("\"approvalId\":\"(apr_[0-9A-Z]+)\"").find(created.contentAsString)!!.groupValues[1]
        val url = "/api/approvals/$id/justification"

        Then("the requester and an APPROVER read it (200, no-store); each read is audited once, without the text") {
            requester.perform(get(url)).let { listOf(it.status, it.contentAsString, it.getHeader("Cache-Control").orEmpty().contains("no-store")) } shouldBe
                listOf(200, """{"approvalId":"$id","purposeReference":"$reference","reason":"$reason"}""", true)
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            loggedIn(approver).perform(get(url)).let { it.status to it.contentAsString } shouldBe
                (200 to """{"approvalId":"$id","purposeReference":"$reference","reason":"$reason"}""")
            audits(id) shouldBe listOf("OK|${requesterAccount.account.id.value}|-", "OK|${approver.account.id.value}|-")
        }

        Then("another OPERATOR, a SECURITY_ADMIN and a VIEWER get 403 FORBIDDEN without the text (each attempt audited); list and detail never carry the text (I-98)") {
            listOf(withRole(AdminRole.OPERATOR), ready.bootstrapped(AdminRole.SECURITY_ADMIN), withRole(AdminRole.VIEWER)).forEach { account ->
                loggedIn(account).perform(get(url)).let { it.status to it.contentAsString } shouldBe (403 to """{"error":"FORBIDDEN"}""")
            }
            audits(id).count { it.startsWith("FORBIDDEN|") } shouldBe 3
            audits(id).forEach {
                it shouldNotContain reference
                it shouldNotContain reason
            }
            val list = requester.perform(get("/api/approvals?view=mine")).contentAsString
            val detail = requester.perform(get("/api/approvals/$id")).contentAsString
            list shouldContain id
            listOf(list, detail).forEach {
                it shouldNotContain reference
                it shouldNotContain reason
            }
        }

        Then("S8 review L-3: a per-actor rate limit (test 5/minute) answers 429 RATE_LIMITED without an audit row, so refused lookups cannot flood the audit log") {
            val prober = withRole(AdminRole.OPERATOR)
            val client = loggedIn(prober)
            val statuses = (1..8).map { client.perform(get(url)).status }
            statuses.toSet() shouldBe setOf(403, 429)
            client.perform(get(url)).let { if (it.status == 429) it.contentAsString shouldBe """{"error":"RATE_LIMITED"}""" }
            val probes = root().use {
                (it.column("SELECT COUNT(*) FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_JUSTIFICATION_VIEWED' AND actor_id = ?", id, prober.account.id.value)
                    .single()).toInt()
            }
            probes shouldBe statuses.count { it == 403 }
            (probes < statuses.size) shouldBe true
        }

        Then("an unknown approval is 404 NOT_FOUND (not audited)") {
            requester.perform(get("/api/approvals/apr_01JC0000000000000000NXST01/justification")).let { it.status to it.contentAsString } shouldBe
                (404 to """{"error":"NOT_FOUND"}""")
            audits("apr_01JC0000000000000000NXST01") shouldBe emptyList()
        }
    }
})
