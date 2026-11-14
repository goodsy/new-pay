package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTestConfig
import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.application.EntitlementCommandFixture
import com.paycore.master.merchant.application.MasterFaultInjectionConfig
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.testcontainers.mysql.MySQLContainer
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * MERCHANT-OPS-002 §8.3 B3-1-5 응답 유실(minor-3 — **새 handler 4개 전부**): 실제 HTTP 서버(RANDOM_PORT·실제 서명 필터) + 실제 MySQL, B2-2-6과 같은 방식.
 * 서명한 요청을 raw 소켓으로 보내고 MASTER 커밋(`admin_request` 결과 행 SUCCESS) 뒤 응답을 읽지 않고 끊는다 → 같은 저장 바이트를 새 `t`·새 서명으로 다시 보내면
 * 200 `replayed=true`·같은 reference, `admin_request` 1행(2인은 근거 행 1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MasterDbTestConfig::class, MasterFaultInjectionConfig::class)
@TestPropertySource(
    properties = [
        "spring.flyway.locations=${MasterDbAccounts.MAIN_MIGRATIONS},${MasterDbAccounts.RUNTIME_PRIVILEGES}," +
            "${MasterDbAccounts.TEST_MIGRATIONS},${MasterDbAccounts.TEST_PRIVILEGES}",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 8",
        "spring.datasource.hikari.maximum-pool-size=16",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ActivationLostResponseSpec(
    environment: Environment,
    private val container: MySQLContainer,
    private val onboarding: MerchantOnboardingUseCase,
    private val clock: com.paycore.master.merchant.application.MutableMasterClock,
) : BehaviorSpec({

    afterEach { clock.reset() }

    val port = environment.getRequiredProperty("local.server.port").toInt()
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }

    /** [loseThenResend]와 같고, 연결을 끊은 뒤·재전송 전에 [between]을 실행한다(B3-2: 재전송 시점의 MASTER 시각을 바꾼다). */
    fun loseThenResendAfter(path: String, body: ByteArray, key: String, between: () -> Unit): Pair<Int, String> {
        val firstT = Instant.now().epochSecond
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            val header = MasterAdminChannelFixture.springHeader("POST", path, body, t = firstT)
            val head = "POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
                "${AdminCallSignature.HEADER}: $header\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(body)
                flush()
            }
            val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
            while (count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ? AND result_kind = 'SUCCESS'", key) == 0L) {
                check(System.nanoTime() < deadline) { "the first request did not commit in time" }
                Thread.sleep(50)
            }
        }
        between()
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json")
                .header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, body, t = firstT + 1))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.statusCode() to response.body()
    }

    /** 첫 요청을 보내고 커밋 뒤 응답을 읽지 않고 끊은 다음, 같은 바이트를 다시 보내 응답 본문을 돌려준다. */
    fun loseThenResend(path: String, body: ByteArray, key: String): Pair<Int, String> = loseThenResendAfter(path, body, key) {}

    Given("each new B3-1 handler whose response is lost after the MASTER commit") {
        Then("payment-method-activations (1 person): the resend replays the same act_; one admin_request row, one activation") {
            val ready = OnboardingFixture.readyMerchant(onboarding)
            val mid = OnboardingFixture.succeeded(onboarding.issueFirstDefaultMerchantAccount(ready.issueCommand()))
            val s = Submission()
            val (status, body) = loseThenResend(ActivationBodies.PAYMENT_METHOD_ACTIVATIONS, ActivationBodies.registerActivation(mid, s).toByteArray(), s.idempotencyKey)
            val act = OnboardingFixture.runtime(container).use { it.scalar("SELECT activation_id FROM payment_method_activation WHERE merchant_account_id = ?", mid) as String }
            status shouldBe 200
            body shouldBe """{"outcome":"SUCCEEDED","reference":"$act","replayed":true}"""
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", s.idempotencyKey) shouldBe 1L
            count("SELECT COUNT(*) FROM payment_method_activation WHERE merchant_account_id = ?", mid) shouldBe 1L
        }

        Then("site-review-rejections (1 person): the resend replays site#1; one decision row") {
            val ready = OnboardingFixture.readyMerchant(onboarding)
            val s = Submission()
            val (status, body) = loseThenResend(ActivationBodies.SITE_REVIEW_REJECTIONS, ActivationBodies.rejectSiteReview(ready.siteId.value, s).toByteArray(), s.idempotencyKey)
            status shouldBe 200
            body shouldBe """{"outcome":"SUCCEEDED","reference":"${ready.siteId.value}#1","replayed":true}"""
            count("SELECT COUNT(*) FROM merchant_site_review_decision WHERE merchant_site_id = ?", ready.siteId.value) shouldBe 1L
        }

        Then("payment-method-activation-approvals and site-review-approvals (2 persons): the resend replays; one admin_request row and one evidence row each") {
            val (ready, ids) = EntitlementCommandFixture.issuedWithActivation(onboarding)
            val cases = listOf(
                Triple(ActivationBodies.PAYMENT_METHOD_ACTIVATION_APPROVALS, Approval(), ids.second.value),
                Triple(ActivationBodies.SITE_REVIEW_APPROVALS, Approval(), "${ready.siteId.value}#1"),
            )
            cases.forEach { (path, a, reference) ->
                withClue(path) {
                    val bytes = if (path == ActivationBodies.SITE_REVIEW_APPROVALS) {
                        ActivationBodies.siteReviewApproval(ready.siteId.value, a)
                    } else {
                        ActivationBodies.activationApproval(ids.second.value, ready.siteId.value, a)
                    }.toByteArray()
                    val (status, body) = loseThenResend(path, bytes, a.idempotencyKey)
                    status shouldBe 200
                    body shouldBe """{"outcome":"SUCCEEDED","reference":"$reference","replayed":true}"""
                    count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", a.idempotencyKey) shouldBe 1L
                    count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", a.approvalId) shouldBe 1L
                }
            }
            count("SELECT COUNT(*) FROM site_activation_confirmation WHERE activation_id = ?", ids.second.value) shouldBe 1L
        }

        Then("B3-2 currency-enablements (2 persons, AT_EXECUTION — §8.4 B3-2-4): the resend at a later MASTER time replays the same cur_; effective_from does not move") {
            val mid = EntitlementCommandFixture.issuedWithActivation(onboarding).second.first.value
            val a = Approval()
            val bytes = CurrencyBodies.body(mid, a = a).toByteArray()
            val first = Instant.parse("2026-10-09T02:00:05.000Z")
            clock.set(first)
            // 커밋 뒤 응답 유실 → 재전송 직전에 MASTER 시각이 흐른다(회복 재전송이 늦게 도착한 경우).
            val (status, body) = loseThenResendAfter(CurrencyBodies.CURRENCY_ENABLEMENTS, bytes, a.idempotencyKey) { clock.set(first.plusSeconds(40)) }
            val reference = OnboardingFixture.runtime(container).use { it.scalar("SELECT enablement_id FROM currency_enablement WHERE merchant_account_id = ?", mid) as String }
            status shouldBe 200
            body shouldBe """{"outcome":"SUCCEEDED","reference":"$reference","replayed":true}"""
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", a.idempotencyKey) shouldBe 1L
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", a.approvalId) shouldBe 1L
            count("SELECT COUNT(*) FROM currency_enablement WHERE merchant_account_id = ?", mid) shouldBe 1L
            OnboardingFixture.runtime(container).use { it.scalar("SELECT CAST(effective_from AS CHAR) FROM currency_enablement WHERE merchant_account_id = ?", mid) }
                .toString() shouldBe "2026-10-09 02:00:05.000"
        }
    }
})
