package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTestConfig
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.application.MasterFaultInjectionConfig
import com.paycore.master.merchant.application.OnboardingFixture
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
 * MERCHANT-OPS-002 §8 M-2a·§8.1 B1-1-6(a) 응답 유실(Recoverability) — **실제 HTTP 서버**(RANDOM_PORT, 실제 Tomcat·실제 서명 필터) + 실제 MySQL:
 * 서명한 명령 요청(사업자 생성 BIZ-2-5·계약 초안 — 옛 `merchant-operations/businesses` 사례는 BUSINESS-PROFILE-001 C-27 전환에서 경로와 함께 제거, 같은 성질을 BIZ-2-5 사례가 새 경로로 고정)을 raw 소켓으로 보내고, MASTER 커밋(서버 측 신호 = `admin_request` 결과 행 SUCCESS 폴링) 뒤 **응답을 한 바이트도 읽지 않고 연결을 끊는다**.
 * 같은 키·같은 본문을 새 `t`·새 서명으로 다시 보내면 200 `replayed=true`·같은 reference이고, 그 키 `admin_request` 정확히 1행, 그 등록번호 `business` 정확히 1행, 변경 이력·Outbox 각 1건이다.
 *
 * 공용 MOCK 컨텍스트와 다른 웹 환경이라 별도 컨텍스트·컨테이너가 생긴다 — 메모리 상한 때문에 이 Spec이 끝나면 닫는다(`@DirtiesContext`).
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
class MerchantOperationsLostResponseSpec(
    environment: Environment,
    private val container: MySQLContainer,
    private val onboarding: com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase,
) : BehaviorSpec({

    val port = environment.getRequiredProperty("local.server.port").toInt()
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }

    Given("BUSINESS-PROFILE-001 BIZ-2-5: a signed business create whose response is lost after the MASTER commit (same real-HTTP context — no extra container)") {
        Then("resending the same key and body (new t, new signature) replays the committed result; exactly one admin_request, business, history and Outbox row") {
            val target = BusinessBodies.CREATE
            val s = Submission()
            val brn = com.paycore.master.merchant.domain.BusinessFixtures.registrationNumber().value
            val body = BusinessBodies.create(s, number = brn, content = BusinessBodies.corporation(name = "응답 유실 법인")).toByteArray(Charsets.UTF_8)
            val firstT = Instant.now().epochSecond
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                val header = MasterAdminChannelFixture.springHeader("POST", target, body, t = firstT)
                val head = "POST $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
                    "${AdminCallSignature.HEADER}: $header\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.US_ASCII))
                    write(body)
                    flush()
                }
                val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
                while (count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ? AND result_kind = 'SUCCESS'", s.idempotencyKey) == 0L) {
                    check(System.nanoTime() < deadline) { "the first request did not commit in time" }
                    Thread.sleep(50)
                }
                // 응답을 한 바이트도 읽지 않고 끊는다.
            }
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$target"))
                    .header("Content-Type", "application/json")
                    .header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", target, body, t = firstT + 1))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            response.statusCode() shouldBe 200
            val node = JsonMapper.builder().build().readTree(response.body())
            node.get("outcome").asString() shouldBe "SUCCEEDED"
            node.get("replayed").asBoolean() shouldBe true
            val businessId = node.get("reference").asString()
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", s.idempotencyKey) shouldBe 1L
            count("SELECT COUNT(*) FROM business WHERE registration_number = ?", brn) shouldBe 1L
            count("SELECT COUNT(*) FROM business_representative WHERE business_id = ?", businessId) shouldBe 2L
            count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ?", businessId) shouldBe 1L
            count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", businessId) shouldBe 1L
            OnboardingFixture.runtime(container).use {
                it.scalar("SELECT correlation_id FROM outbox_event WHERE aggregate_id = ?", businessId)
            } shouldBe s.correlationId
        }
    }

    Given("B1-2: a signed registerContract whose response is lost after the MASTER commit (§8.1 B1-2-1 — same property as B1-1-6a)") {
        val json = JsonMapper.builder().build()
        fun send(target: String, bytes: ByteArray, t: Long = Instant.now().epochSecond): HttpResponse<String> = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$target"))
                .header("Content-Type", "application/json")
                .header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", target, bytes, t = t))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        fun reference(r: HttpResponse<String>): String = json.readTree(r.body()).get("reference").asString()

        Then("resending the same key and body replays the committed contract; exactly one admin_request row and one contract row for the merchant") {
            // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 준비용 사업자는 새 생성 경로(REGISTERED — D-BIZ-001).
            val businessId = reference(send(BusinessBodies.CREATE, BusinessBodies.create(number = OnboardingFixture.registrationNumber().value).toByteArray(Charsets.UTF_8)))
                .also { OnboardingFixture.contracted(onboarding, com.paycore.master.merchant.domain.BusinessId(it)) } // FLOW-3 입력 보강(이 실제 HTTP 컨텍스트의 Use Case)
            val merchantId = reference(send(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant(businessId).toByteArray(Charsets.UTF_8)))
            val target = MerchantOperationsBodies.CONTRACTS
            val s = Submission()
            val body = MerchantOperationsBodies.registerContract(businessId, merchantId, s = s).toByteArray(Charsets.UTF_8)
            val firstT = Instant.now().epochSecond

            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                val header = MasterAdminChannelFixture.springHeader("POST", target, body, t = firstT)
                val head = "POST $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
                    "${AdminCallSignature.HEADER}: $header\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.US_ASCII))
                    write(body)
                    flush()
                }
                val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
                while (count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ? AND result_kind = 'SUCCESS'", s.idempotencyKey) == 0L) {
                    check(System.nanoTime() < deadline) { "the first request did not commit in time" }
                    Thread.sleep(50)
                }
            }

            val response = send(target, body, t = firstT + 1)
            response.statusCode() shouldBe 200
            val node = json.readTree(response.body())
            node.get("outcome").asString() shouldBe "SUCCEEDED"
            node.get("replayed").asBoolean() shouldBe true
            val contractId = node.get("reference").asString()
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", s.idempotencyKey) shouldBe 1L
            count("SELECT COUNT(*) FROM contract WHERE merchant_id = ?", merchantId) shouldBe 1L
            count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", contractId) shouldBe 1L
            OnboardingFixture.runtime(container).use {
                it.scalar("SELECT correlation_id FROM outbox_event WHERE aggregate_id = ?", contractId)
            } shouldBe s.correlationId
        }
    }
})
