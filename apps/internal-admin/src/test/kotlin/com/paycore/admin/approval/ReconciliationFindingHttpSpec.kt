package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubPaymentOpsServer
import com.paycore.admin.support.column
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
 * 대사 발견 조회(ADMIN-PAYMENT-OPS-001 S8 §4.17·§4.20 — `ReconciliationFindingListSpec`): `GET /api/reconciliation/findings`는 SECURITY_ADMIN·APPROVER만(그 밖 403),
 * 열린 발견 수(종류별)·목록(jOOQ Reader)·마지막 실행 정보. 발견 행은 code 열만 — 승인 사유·근거 문구는 어떤 필드에도 없다(비노출).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class ReconciliationFindingHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    val mapper = JsonMapper.builder().build()
    fun loggedIn(account: LoginReadyAccounts.LoginReady, at: Instant = Instant.now()) =
        AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret, at).status shouldBe 200 }
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    fun root() = AdminDbAccounts.root(container)
    val reason = "secret-free reason text 4242"
    val reference = "RECON-2026-4242"

    Given("findings recorded by the reconciliation job, one of them for a real approval with a reason text") {
        val operator = loggedIn(withRole(AdminRole.OPERATOR))
        val pay = "pay_01JC" + (System.nanoTime() % 1_000_000L * 100 + 57).toString().padStart(22, '0')
        StubPaymentOpsServer.targetFound(pay)
        val created = operator.perform(
            post("/api/approvals/operator-correction-cancel").contentType(MediaType.APPLICATION_JSON).content(
                """{"paymentId":"$pay","amountMinor":7000,"purposeKind":"INCIDENT_OR_RECON_CORRECTION","purposeReference":"$reference","reason":"$reason"}""",
            ),
        )
        val id = Regex("\"approvalId\":\"(apr_[0-9A-Z]+)\"").find(created.contentAsString)!!.groupValues[1]
        root().use {
            it.update(
                "INSERT INTO admin_ops_reconciliation_finding (kind, approval_id, payment_ref, detail_code, found_at) VALUES ('DECISION_FOR_UNAPPROVED', ?, ?, 'AMOUNT,REQUESTER', UTC_TIMESTAMP(3))",
                id, pay,
            )
            it.update(
                "INSERT INTO admin_ops_reconciliation_finding (kind, approval_id, payment_ref, detail_code, found_at) VALUES ('ORPHAN_DECISION', 'apr_01JC0000000000000000HTPF01', NULL, NULL, UTC_TIMESTAMP(3))",
            )
        }
        val dbCounts = root().use { c -> c.column("SELECT CONCAT(kind, '|', COUNT(*)) FROM admin_ops_reconciliation_finding GROUP BY kind") }
            .associate { it.substringBefore('|') to it.substringAfter('|').toLong() }

        Then("SECURITY_ADMIN and APPROVER read open counts per kind and code-only rows (no-store); OPERATOR and VIEWER get 403") {
            listOf(ready.bootstrapped(AdminRole.SECURITY_ADMIN), ready.bootstrapped(AdminRole.APPROVER)).forEach { account ->
                val response = loggedIn(account).perform(get("/api/reconciliation/findings"))
                response.status shouldBe 200
                response.getHeader("Cache-Control").orEmpty().contains("no-store") shouldBe true
                val body = mapper.readTree(response.contentAsString)
                body.propertyNames().toSet() shouldBe setOf("openCounts", "openTotal", "findings", "truncated", "lastRunAt", "lastFullScanAt", "lastResult")
                body["openCounts"].propertyNames().associateWith { body["openCounts"][it].asLong() } shouldBe dbCounts
                body["openTotal"].asLong() shouldBe dbCounts.values.sum()
                val rows = body["findings"].toList()
                rows.map { it.propertyNames().toSet() }.toSet() shouldBe setOf(setOf("findingId", "kind", "approvalId", "paymentRef", "detailCode", "foundAt"))
                rows.single { it["approvalId"].asString() == id }.let { listOf(it["kind"].asString(), it["paymentRef"].asString(), it["detailCode"].asString()) } shouldBe
                    listOf("DECISION_FOR_UNAPPROVED", pay, "AMOUNT,REQUESTER")
                response.contentAsString shouldNotContain reason
                response.contentAsString shouldNotContain reference
            }
            listOf(withRole(AdminRole.OPERATOR), withRole(AdminRole.VIEWER)).forEach { account ->
                loggedIn(account).perform(get("/api/reconciliation/findings")).let { it.status to it.contentAsString } shouldBe (403 to """{"error":"FORBIDDEN"}""")
            }
        }
    }
})
