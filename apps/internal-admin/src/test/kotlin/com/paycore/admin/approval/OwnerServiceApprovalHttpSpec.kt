package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubPaymentOpsServer
import com.paycore.admin.support.TestTotp
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
import java.time.Instant

/**
 * 소유 서비스 승인 HTTP(ADMIN-PAYMENT-OPS-001 §4.10 M-6 0단계 분기·§4.12·§4.20 — 새 endpoint CSRF·역할, `no-store`, 화면 대상 조회 역할·속도 제한 429).
 * 실제 2단계 로그인 세션, PAYMENT는 실제 HTTP 대역.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class OwnerServiceApprovalHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    var seq = System.nanoTime() % 1_000_000L * 100
    fun paymentId(): String = "pay_01JC" + (++seq).toString().padStart(22, '0')
    fun loggedIn(account: LoginReadyAccounts.LoginReady, at: Instant = Instant.now()) =
        AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret, at).status shouldBe 200 }
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    fun requestBody(paymentId: String, amount: Long = 7000) =
        """{"paymentId":"$paymentId","amountMinor":$amount,"purposeKind":"INCIDENT_OR_RECON_CORRECTION","purposeReference":"RECON-2026-0001","reason":"duplicate charge correction"}"""

    Given("the operator correction cancel endpoints") {
        Then("OPERATOR requests (201) → APPROVER approves on the common approve path, routed to the 2-TX service (200 EXECUTED); responses are no-store") {
            val pay = paymentId()
            StubPaymentOpsServer.targetFound(pay)
            val created = loggedIn(withRole(AdminRole.OPERATOR)).perform(
                post("/api/approvals/operator-correction-cancel").contentType(MediaType.APPLICATION_JSON).content(requestBody(pay)),
            )
            created.status shouldBe 201
            created.getHeader("Cache-Control").orEmpty() shouldContain "no-store"
            val id = Regex("\"approvalId\":\"(apr_[0-9A-Z]+)\"").find(created.contentAsString)!!.groupValues[1]
            StubPaymentOpsServer.execution(id, StubPaymentOpsServer.Reply(200, "{\"cancellationId\":\"pcn_01JC000000000000000000HT01\",\"status\":\"IN_PROGRESS\",\"existing\":false}"))
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val approved = loggedIn(approver).perform(
                post("/api/approvals/$id/approve").contentType(MediaType.APPLICATION_JSON).content(AdminHttpClient.json("totpCode" to TestTotp.code(approver.secret, Instant.now().plusSeconds(30)))),
            )
            (approved.status to approved.contentAsString) shouldBe (200 to """{"approvalId":"$id","status":"EXECUTED"}""")
            val detail = loggedIn(ready.bootstrapped(AdminRole.APPROVER)).perform(get("/api/approvals/$id")).contentAsString
            detail shouldContain "\"targetKind\":\"PAYMENT\""
            detail shouldContain "\"targetRef\":\"$pay\""
            detail shouldNotContain "duplicate charge correction"
            // S8 화면용 상세(명령·실행 LEFT JOIN — I-98): 고정 금액·통화·목적 종류·고정 대상, 실행 상태(시도·소유 결과). 사유·근거 문구는 내보내지 않는다.
            detail shouldContain "\"owner\":{"
            detail shouldContain "\"amountMinor\":7000"
            detail shouldContain "\"purposeKind\":\"INCIDENT_OR_RECON_CORRECTION\""
            detail shouldContain "\"ownerReference\":\"pcn_01JC000000000000000000HT01\""
            detail shouldContain "\"attemptCount\":1"
            detail shouldContain "\"pinnedTarget\":{"
            detail shouldNotContain "RECON-2026-0001"
        }
        Then("a SECURITY_ADMIN cannot request (403); without the CSRF header the request is refused (403); a malformed input is 400 with its field and reason (I-111)") {
            val pay = paymentId()
            StubPaymentOpsServer.targetFound(pay)
            loggedIn(ready.bootstrapped(AdminRole.SECURITY_ADMIN)).perform(
                post("/api/approvals/operator-correction-cancel").contentType(MediaType.APPLICATION_JSON).content(requestBody(pay)),
            ).status shouldBe 403
            val operator = loggedIn(withRole(AdminRole.OPERATOR))
            operator.perform(post("/api/approvals/operator-correction-cancel").contentType(MediaType.APPLICATION_JSON).content(requestBody(pay)), xsrf = false).status shouldBe 403
            operator.perform(post("/api/approvals/operator-correction-cancel").contentType(MediaType.APPLICATION_JSON).content(requestBody(pay, 0)))
                .let { it.status to it.contentAsString } shouldBe (400 to """{"error":"INVALID_INPUT","field":"amountMinor","reason":"NOT_POSITIVE"}""")
            // I-111(S8): 근거·사유 거절 이유를 고정 code로(값 원문 없음). 날짜·일련번호가 이어진 참조는 카드 번호 패턴(LIVE Gate L-B 메모 — 동작은 보수적 거부 그대로).
            val dated = requestBody(pay).replace("RECON-2026-0001", "RECON-20261028-000001")
            operator.perform(post("/api/approvals/operator-correction-cancel").contentType(MediaType.APPLICATION_JSON).content(dated))
                .let { it.status to it.contentAsString } shouldBe (400 to """{"error":"INVALID_INPUT","field":"purposeReference","reason":"CARD_NUMBER_PATTERN"}""")
            val spaced = requestBody(pay).replace("\"reason\":\"duplicate", "\"reason\":\" duplicate")
            operator.perform(post("/api/approvals/operator-correction-cancel").contentType(MediaType.APPLICATION_JSON).content(spaced))
                .let { it.status to it.contentAsString } shouldBe (400 to """{"error":"INVALID_INPUT","field":"reason","reason":"EDGE_WHITESPACE"}""")
        }
    }

    Given("the screen target lookup GET /api/payment-ops/payments/{paymentId}/target (I-23)") {
        Then("every viewing role reads it (VIEWER 200), the response has the target fields only; bad id 400; unknown 404; over the per-minute limit 429") {
            val pay = paymentId()
            StubPaymentOpsServer.targetFound(pay, revision = 5, inFlight = true)
            val viewer = loggedIn(withRole(AdminRole.VIEWER))
            viewer.perform(get("/api/payment-ops/payments/$pay/target")).let { it.status to it.contentAsString } shouldBe (
                200 to """{"paymentId":"$pay","paymentStatus":"AUTHORIZED","cancellable":true,"currency":"KRW","authorizedMinor":10000,"cancellableMinor":10000,""" +
                    """"balanceRevision":5,"onHold":false,"inFlightCancel":true}"""
                )
            viewer.perform(get("/api/payment-ops/payments/pay_x/target")).status shouldBe 400
            viewer.perform(get("/api/payment-ops/payments/${paymentId()}/target")).let { it.status to it.contentAsString } shouldBe (404 to """{"error":"PAYMENT_NOT_FOUND"}""")
            // 시험 설정 lookup-per-minute = 5: 위 2회(형식 오류는 세지 않음) + 3회 = 5, 다음은 429(분 경계에 걸리면 창이 새로 시작되므로 6회 더 시도해 한 번 이상 429).
            val statuses = (1..8).map { viewer.perform(get("/api/payment-ops/payments/$pay/target")).status }
            statuses.contains(429) shouldBe true
        }
    }
})
