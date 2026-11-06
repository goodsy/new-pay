package com.paycore.admin.financialops

import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubFinancialOpsServer
import com.paycore.admin.support.SwitchableAuditStore
import com.paycore.admin.support.SwitchableAuditStoreConfig
import com.paycore.admin.support.column
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
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

/**
 * 매입 파일 상세·레코드 쪽 감사 저장 실패(ACQ-VIEW-001 §4.6 D-ACQV-001 — B2 리뷰 MINOR-1, 실제 로그인 세션 + 실제 MySQL + 감사 저장 실패 주입): `ACQUISITION_FILE_VIEWED` 기록이 실패하면
 * 503 `AUDIT_UNAVAILABLE`이고 **FINANCIAL 값을 돌려주지 않는다**(본문에 파일·레코드 값 없음, `no-store`, 감사 행 0). 컨텍스트는 `TransactionAuditFailureHttpSpec`과 같은 구성(새 Spring
 * 컨텍스트를 늘리지 않는다 — TEST-PERF-002). 조회 역할은 부트스트랩 경로의 APPROVER(VIEW_MASKED 4역할 중 하나).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(LoginReadyAccounts::class, SwitchableAuditStoreConfig::class)
class AcquisitionAuditFailureHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val auditStore: SwitchableAuditStore,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubFinancialOpsServer.start()
    afterEach { auditStore.failOn(null) }
    val file = "ACQ.AUDF${System.nanoTime() % 100_000L}.TEST.20261020.001"
    val sha = "5c8b8097".repeat(8)
    StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(file), StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.detailJson(file)))
    StubFinancialOpsServer.reply(
        StubFinancialOpsServer.recordsPath(file),
        StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.recordsJson(StubFinancialOpsServer.recordJson(1, transactionId = "ptx_audit_fail_1"))),
    )

    Given("an audit store that fails on ACQUISITION_FILE_VIEWED") {
        Then("the file detail and a record page are 503 AUDIT_UNAVAILABLE with no FINANCIAL value in the body and no audit row; with a working store both are 200 and audited") {
            val account = ready.bootstrapped(AdminRole.APPROVER)
            val client = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
            fun audits() = AdminDbAccounts.root(container).use {
                it.column(
                    "SELECT CONCAT(action, '|', result_code, '|', COALESCE(target_ref, '-')) FROM admin_audit_event WHERE actor_id = ? AND action = 'ACQUISITION_FILE_VIEWED'",
                    account.account.id.value,
                )
            }
            fun detail() = client.perform(get("/api/acquisition-files/$file"))
            fun records() = client.perform(post("/api/acquisition-files/$file/record-searches").contentType(MediaType.APPLICATION_JSON).content("{}"))
            auditStore.failOn { it.action == "ACQUISITION_FILE_VIEWED" }
            listOf(detail(), records()).forEach {
                it.status shouldBe 503
                it.contentAsString shouldBe """{"error":"AUDIT_UNAVAILABLE"}"""
                listOf(sha, "ptx_auth_pay_fx_1", "ptx_audit_fail_1", "demo-mid-001", "acq-cutoff-demo-1").forEach { value -> it.contentAsString shouldNotContain value }
                it.getHeader("Cache-Control")!! shouldContain "no-store"
            }
            // FINANCIAL은 답했다(본 것이 있으니 감사를 시도했다) — 감사 실패만이 결과를 막았다.
            StubFinancialOpsServer.requestsForPath(StubFinancialOpsServer.filePath(file)).size shouldBe 1
            StubFinancialOpsServer.requestsForPath(StubFinancialOpsServer.recordsPath(file)).size shouldBe 1
            audits().shouldBeEmpty()
            auditStore.failOn(null)
            detail().let {
                it.status shouldBe 200
                it.contentAsString shouldContain sha
            }
            records().let {
                it.status shouldBe 200
                it.contentAsString shouldContain "ptx_audit_fail_1"
            }
            audits() shouldBe listOf("ACQUISITION_FILE_VIEWED|OK|$file", "ACQUISITION_FILE_VIEWED|OK|$file")
        }
    }
})
