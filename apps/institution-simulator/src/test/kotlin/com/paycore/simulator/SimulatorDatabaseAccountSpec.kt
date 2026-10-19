package com.paycore.simulator

import com.paycore.simulator.support.SimulatorDb
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Simulator DB 표 단위 runtime 권한(AUTH-001 §5.5·AU-20, rev 0.3 m-4). 가상 VAN 원장은 회복 증명(AC1 §6-5 "Simulator 승인 1, 재승인 없음")의 기준이라 append-only 표에
 * UPDATE/DELETE 권한이 없어야 한다(DB가 1142로 거부). 초기화 스크립트(docker/simulator-mysql/init)는 재실행 안전이며 옛 볼륨(runtime = DB 단위 DML)도 1회 재실행으로
 * 수렴한다. 옛 볼륨을 고치지 않은 채로는 Simulator가 기동을 거부한다. 이 spec은 자기 컨테이너를 쓴다(권한을 바꾸므로 다른 spec과 공유하지 않는다).
 */
class SimulatorDatabaseAccountSpec : FunSpec({

    val container = SimulatorDb.newContainer().also { it.start() }
    afterSpec { container.stop() }

    fun connect(user: String, password: String): Connection = DriverManager.getConnection(SimulatorDb.url(container), user, password)
    fun root(): Connection = connect("root", container.password)
    fun runtime(): Connection = connect(SimulatorDb.RUNTIME_USER, SimulatorDb.password(SimulatorDb.RUNTIME_USER))

    fun Connection.grants(account: String): List<String> = createStatement().use { s ->
        s.executeQuery("SHOW GRANTS FOR $account").use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
    }

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

    fun rerunInitScript() {
        val r = container.execInContainer("bash", "/docker-entrypoint-initdb.d/01-simulator-database.sh")
        withClueExit(r.exitCode, r.stderr)
    }

    // 첫 기동이 migration(V1~V4: 업무 표 + 표 단위 권한)을 적용한다.
    start().close()

    test("the runtime account holds only the runtime role; the role holds exactly the table privileges of the write paths") {
        root().use { c ->
            c.grants("'paycore_simulator'@'%'") shouldContainExactlyInAnyOrder listOf(
                "GRANT USAGE ON *.* TO `paycore_simulator`@`%`",
                "GRANT `paycore_simulator_runtime`@`%` TO `paycore_simulator`@`%`",
            )
            c.grants("'paycore_simulator_runtime'@'%'") shouldContainExactlyInAnyOrder listOf(
                "GRANT USAGE ON *.* TO `paycore_simulator_runtime`@`%`",
                "GRANT SELECT, INSERT, UPDATE ON `paycore_simulator`.`authentication_transaction` TO `paycore_simulator_runtime`@`%`",
                "GRANT SELECT, INSERT ON `paycore_simulator`.`lookup_log` TO `paycore_simulator_runtime`@`%`",
                "GRANT SELECT, INSERT ON `paycore_simulator`.`van_ledger_entry` TO `paycore_simulator_runtime`@`%`",
                "GRANT SELECT, INSERT ON `paycore_simulator`.`van_inquiry_log` TO `paycore_simulator_runtime`@`%`",
                "GRANT SELECT, INSERT ON `paycore_simulator`.`van_evidence_use` TO `paycore_simulator_runtime`@`%`",
                "GRANT SELECT, INSERT, UPDATE ON `paycore_simulator`.`van_pending_decision` TO `paycore_simulator_runtime`@`%`",
            )
        }
    }

    test("the runtime account cannot UPDATE or DELETE the ledger, the inquiry log or the evidence use (1142), nor run DDL") {
        runtime().use { c ->
            listOf(
                "UPDATE van_ledger_entry SET outcome = 'APPROVED' WHERE 1 = 0",
                "DELETE FROM van_ledger_entry WHERE 1 = 0",
                "UPDATE van_inquiry_log SET result = 'FOUND' WHERE 1 = 0",
                "DELETE FROM van_inquiry_log WHERE 1 = 0",
                "DELETE FROM van_evidence_use WHERE 1 = 0",
                "DELETE FROM van_pending_decision WHERE 1 = 0",
                "DELETE FROM authentication_transaction WHERE 1 = 0",
                "DROP TABLE van_ledger_entry",
            ).forEach { sql ->
                val e = shouldThrow<SQLException> { c.createStatement().use { it.executeUpdate(sql) } }
                e.errorCode shouldBe 1142
            }
            c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM van_ledger_entry").use { rs -> rs.next() shouldBe true } }
        }
    }

    // 검토 #2 n-1: 기동 검사는 lookup_log UPDATE/DELETE와 van_pending_decision DELETE도 본다.
    test("a table-level UPDATE on lookup_log or DELETE on van_pending_decision is refused at startup (review #2 n-1)") {
        listOf("UPDATE ON paycore_simulator.lookup_log", "DELETE ON paycore_simulator.van_pending_decision").forEach { grant ->
            root().use { c -> c.createStatement().use { it.execute("GRANT $grant TO 'paycore_simulator_runtime'@'%'") } }
            shouldThrow<Exception> { start().close() }
            root().use { c -> c.createStatement().use { it.execute("REVOKE $grant FROM 'paycore_simulator_runtime'@'%'") } }
        }
        start().close()
    }

    test("an old volume (runtime with database-level DML) is refused at startup, and one re-run of the init script converges it") {
        root().use { c -> c.createStatement().use { it.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON paycore_simulator.* TO 'paycore_simulator'@'%'") } }
        val failure = shouldThrow<Exception> { start().close() }
        generateSequence(failure as Throwable) { it.cause?.takeIf { c -> c !== it } }.joinToString(" | ") { it.message ?: "" } shouldContain "van ledger"
        rerunInitScript()
        rerunInitScript() // 재실행 안전(두 번째도 같은 상태)
        root().use { c ->
            c.grants("'paycore_simulator'@'%'") shouldContainExactlyInAnyOrder listOf(
                "GRANT USAGE ON *.* TO `paycore_simulator`@`%`",
                "GRANT `paycore_simulator_runtime`@`%` TO `paycore_simulator`@`%`",
            )
        }
        start().close()
    }
})

private fun withClueExit(exitCode: Int, stderr: String) {
    check(exitCode == 0) { "init script failed ($exitCode): $stderr" }
}
