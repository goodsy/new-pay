package com.paycore.simulator

import com.paycore.simulator.support.SimulatorDb
import com.paycore.simulator.support.SimulatorMySqlContainerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.testcontainers.mysql.MySQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager

/**
 * Simulator 실행 단위 골격(AUTHN-001 S5): 별도 프로세스·포트(설정 값)·Simulator 전용 MySQL(runtime 계정)로 기동하고 health가 UP이다. runtime 계정은 DDL을
 * 할 수 없다(DML만 — AN-19).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["PAYCORE_SIMULATOR_PORT=0"])
@Import(SimulatorMySqlContainerConfig::class)
class SimulatorBootSpec(
    private val env: Environment,
    private val container: MySQLContainer,
) : FunSpec({

    test("the simulator starts on its configured port against its own database and reports health UP") {
        val port = env.getRequiredProperty("local.server.port").toInt()
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/actuator/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        response.statusCode() shouldBe 200
        response.body() shouldContain "\"status\":\"UP\""
    }

    test("the runtime account has DML only on paycore_simulator (no DDL)") {
        DriverManager.getConnection(SimulatorDb.url(container), SimulatorDb.RUNTIME_USER, SimulatorDb.password(SimulatorDb.RUNTIME_USER)).use { c ->
            val grants = c.createStatement().use { st -> st.executeQuery("SHOW GRANTS").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
            grants.joinToString("\n") shouldContain "GRANT SELECT, INSERT, UPDATE, DELETE ON `paycore_simulator`.* TO `paycore_simulator`@`%`"
            val denied = runCatching { c.createStatement().use { it.execute("CREATE TABLE ddl_probe (id INT)") } }.exceptionOrNull()
            (denied as java.sql.SQLException).errorCode shouldBe 1142
        }
    }
})
