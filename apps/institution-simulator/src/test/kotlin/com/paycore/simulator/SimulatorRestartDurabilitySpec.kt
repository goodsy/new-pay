package com.paycore.simulator

import com.paycore.simulator.support.SimulatorDb
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * 기관 사실 존속(AUTHN-001 §13.1·§15 Recoverability): Simulator 프로세스를 다시 띄워도 같은 Simulator 전용 DB의 결과 조회 본문이 같다 — PG 재시작·rollback과
 * 무관하게 기관 쪽 결과가 남는다. 같은 컨테이너에 Simulator 앱을 두 번 차례로 기동한다(운영 계정·운영 migration).
 */
class SimulatorRestartDurabilitySpec : FunSpec({

    val container = SimulatorDb.newContainer().also { it.start() }
    afterSpec { container.stop() }
    val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    val key = "test-only-simulator-client-key-1"

    fun start(): ConfigurableApplicationContext = SpringApplicationBuilder(InstitutionSimulatorApplication::class.java).properties(
        mapOf(
            "PAYCORE_SIMULATOR_PORT" to "0",
            "PAYCORE_SIMULATOR_DB_URL" to SimulatorDb.url(container),
            "PAYCORE_SIMULATOR_DB_USERNAME" to SimulatorDb.RUNTIME_USER,
            "PAYCORE_SIMULATOR_DB_PASSWORD" to SimulatorDb.password(SimulatorDb.RUNTIME_USER),
            "PAYCORE_SIMULATOR_DB_MIGRATION_USERNAME" to SimulatorDb.MIGRATION_USER,
            "PAYCORE_SIMULATOR_DB_MIGRATION_PASSWORD" to SimulatorDb.password(SimulatorDb.MIGRATION_USER),
        ),
    ).run()

    fun port(c: ConfigurableApplicationContext) = c.environment.getRequiredProperty("local.server.port")

    test("a completed result is looked up with the same body after the simulator restarts") {
        val first = start()
        val base1 = "http://127.0.0.1:${port(first)}"
        val register = http.send(
            HttpRequest.newBuilder(URI("$base1/sim/v1/authentications")).header("Authorization", "Bearer $key").header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"paymentId":"pay_restart","merchantRef":"mid-demo-0001","environment":"TEST","amountMinor":10000,"currency":"KRW",""" +
                            """"plan":{"kind":"INSTALLMENT","months":3},"cardCompany":"SIMISS-A","state":"${"r".repeat(43)}",""" +
                            """"returnUrl":"https://checkout.example.test/checkout/s/Hh0123456789abcdefghij/authentication/return"}""",
                    ),
                ).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        register.statusCode() shouldBe 201
        val txId = Regex("\"authenticationTransactionId\":\"(sat_[^\"]+)\"").find(register.body())!!.groupValues[1]
        val redirect = http.send(
            HttpRequest.newBuilder(URI("$base1/sim/authenticate/$txId")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("action=authenticate&card=VC-CREDIT-OK")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val ref = redirect.headers().firstValue("Location").get().substringAfter("resultRef=").substringBefore('&')
        fun lookup(base: String) = http.send(
            HttpRequest.newBuilder(URI("$base/sim/v1/authentication-results/$ref")).header("Authorization", "Bearer $key").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val before = lookup(base1)
        before.statusCode() shouldBe 200
        first.close()

        val second = start()
        try {
            val after = lookup("http://127.0.0.1:${port(second)}")
            after.statusCode() shouldBe 200
            after.body() shouldBe before.body()
        } finally {
            second.close()
        }
    }
})
