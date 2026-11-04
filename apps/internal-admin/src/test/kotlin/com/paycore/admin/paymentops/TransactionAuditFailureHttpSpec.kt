package com.paycore.admin.paymentops

import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubPaymentOpsServer
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.testcontainers.mysql.MySQLContainer

/**
 * 거래 상세 감사 저장 실패(TXN-OPS-001 §5.1 D-TXOPS-002 — 리뷰 MINOR-5, 실제 로그인 세션 + 실제 MySQL + 감사 저장 실패 주입): `PAYMENT_VIEWED` 기록이 실패하면 503 `AUDIT_UNAVAILABLE`이고
 * **상세를 돌려주지 않는다**(본문에 PAYMENT 값 없음, 감사 행 없음). 컨텍스트는 `LogoutAuditFailureSpec`과 같은 구성(새 Spring 컨텍스트를 늘리지 않는다 — TEST-PERF-002). 조회 역할은
 * 부트스트랩 경로의 APPROVER(VIEW_MASKED 4역할 중 하나 — I-1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(LoginReadyAccounts::class, SwitchableAuditStoreConfig::class)
class TransactionAuditFailureHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val auditStore: SwitchableAuditStore,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    afterEach { auditStore.failOn(null) }
    val pay = "pay_01JF" + (System.nanoTime() % 1_000_000L).toString().padStart(22, '0')
    StubPaymentOpsServer.detail(pay, StubPaymentOpsServer.Reply(200, TransactionJson.detail(pay)))

    Given("an audit store that fails on PAYMENT_VIEWED") {
        Then("the detail is 503 AUDIT_UNAVAILABLE with no PAYMENT value in the body and no audit row; with a working store the same call is 200 and audited") {
            val account = ready.bootstrapped(AdminRole.APPROVER)
            val client = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
            fun audits() = AdminDbAccounts.root(container).use {
                it.column("SELECT CONCAT(action, '|', result_code, '|', COALESCE(target_ref, '-')) FROM admin_audit_event WHERE actor_id = ? AND action = 'PAYMENT_VIEWED'", account.account.id.value)
            }
            auditStore.failOn { it.action == "PAYMENT_VIEWED" }
            client.perform(get("/api/payments/$pay")).let {
                it.status shouldBe 503
                it.contentAsString shouldBe """{"error":"AUDIT_UNAVAILABLE"}"""
                it.contentAsString shouldNotContain TransactionJson.ORDER_FULL
                it.getHeader("Cache-Control")!! shouldContain "no-store"
            }
            StubPaymentOpsServer.requestsForPath("/internal/v1/admin/payments/$pay").size shouldBe 1
            audits().shouldBeEmpty()
            auditStore.failOn(null)
            client.perform(get("/api/payments/$pay")).let {
                it.status shouldBe 200
                it.contentAsString shouldContain TransactionJson.ORDER_FULL
            }
            audits() shouldBe listOf("PAYMENT_VIEWED|OK|$pay")
        }
    }
})
