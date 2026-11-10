package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTestConfig
import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.application.MasterFaultInjectionConfig
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * MERCHANT-OPS-002 §8.2 B2-2-6 응답 유실(Recoverability) — **실제 HTTP 서버**(RANDOM_PORT, 실제 Tomcat·실제 서명 필터) + 실제 MySQL, M-2a(MerchantOperationsLostResponseSpec)와 같은 방식:
 * 서명한 `contract-revision-approvals` 요청을 raw 소켓으로 보내고, MASTER 커밋(서버 측 신호 = `admin_request` 결과 행 SUCCESS) 뒤 응답을 한 바이트도 읽지 않고 끊는다.
 * 같은 저장 바이트를 새 `t`·새 서명으로 다시 보내면 200 `replayed=true`·같은 reference이고, `admin_request`·근거 행·Revision 승인 사실이 각 1이다.
 * 별도 웹 환경이라 컨텍스트·컨테이너가 따로 생긴다 — 끝나면 닫는다(`@DirtiesContext`, 메모리 상한).
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
class MerchantApprovalLostResponseSpec(
    environment: Environment,
    private val container: MySQLContainer,
    private val onboarding: MerchantOnboardingUseCase,
) : BehaviorSpec({

    val port = environment.getRequiredProperty("local.server.port").toInt()
    val path = MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }

    Given("a signed contract revision approval whose response is lost after the MASTER commit") {
        Then("resending the same stored bytes (new t, new signature) replays; exactly one admin_request row, one evidence row and one approval fact") {
            val contractId = OnboardingFixture.readyMerchant(onboarding, approved = false).contractId.value
            val a = Approval()
            val body = MerchantApprovalBodies.contractRevisionApproval(contractId, a = a).toByteArray(Charsets.UTF_8)
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
                while (count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ? AND result_kind = 'SUCCESS'", a.idempotencyKey) == 0L) {
                    check(System.nanoTime() < deadline) { "the first request did not commit in time" }
                    Thread.sleep(50)
                }
                // 응답을 읽지 않고 끊는다(입력 스트림은 한 번도 읽지 않았다).
            }

            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                    .header("Content-Type", "application/json")
                    .header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, body, t = firstT + 1))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            response.statusCode() shouldBe 200
            val node = JsonMapper.builder().build().readTree(response.body())
            node.get("outcome").asString() shouldBe "SUCCEEDED"
            node.get("replayed").asBoolean() shouldBe true
            node.get("reference").asString() shouldBe "$contractId#1"
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", a.idempotencyKey) shouldBe 1L
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", a.approvalId) shouldBe 1L
            count("SELECT COUNT(*) FROM contract_revision_approval WHERE contract_id = ?", contractId) shouldBe 1L
            OnboardingFixture.runtime(container).use {
                it.scalar("SELECT correlation_id FROM outbox_event WHERE event_type = 'ContractRevisionApproved' AND aggregate_id = ?", contractId)
            } shouldBe a.correlationId
        }

        Then("B2-3 (review m-2): the same for first-default-merchant-accounts — resend replays the same mid_; one admin_request row, one evidence row, one MID") {
            val midPath = MerchantApprovalBodies.FIRST_DEFAULT_MERCHANT_ACCOUNTS
            val r = OnboardingFixture.readyMerchant(onboarding)
            val a = Approval()
            val body = MerchantApprovalBodies.firstDefaultIssuance(r.merchantId.value, r.contractId.value, r.agreementId.value, r.siteId.value, a = a).toByteArray(Charsets.UTF_8)
            val firstT = Instant.now().epochSecond
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                val header = MasterAdminChannelFixture.springHeader("POST", midPath, body, t = firstT)
                val head = "POST $midPath HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
                    "${AdminCallSignature.HEADER}: $header\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.US_ASCII))
                    write(body)
                    flush()
                }
                val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
                while (count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ? AND result_kind = 'SUCCESS'", a.idempotencyKey) == 0L) {
                    check(System.nanoTime() < deadline) { "the first request did not commit in time" }
                    Thread.sleep(50)
                }
            }
            val mid = OnboardingFixture.runtime(container).use { it.scalar("SELECT merchant_account_id FROM merchant_account WHERE merchant_id = ?", r.merchantId.value) as String }
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$midPath"))
                    .header("Content-Type", "application/json")
                    .header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", midPath, body, t = firstT + 1))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            response.statusCode() shouldBe 200
            response.body() shouldBe """{"outcome":"SUCCEEDED","reference":"$mid","replayed":true}"""
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", a.idempotencyKey) shouldBe 1L
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", a.approvalId) shouldBe 1L
            count("SELECT COUNT(*) FROM merchant_account WHERE merchant_id = ?", r.merchantId.value) shouldBe 1L
        }
    }
})
