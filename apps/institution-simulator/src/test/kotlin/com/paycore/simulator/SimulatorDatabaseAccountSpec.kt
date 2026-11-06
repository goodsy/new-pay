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

    val container = SimulatorDb.startedContainer()
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

    // 첫 기동이 migration(V1~V12: 업무 표 + 표·열 단위 권한, CANCEL-001 V5~V7, WEBHOOK-001 V8~V9, ACQUIRING-001 2-E V10~V12 포함)을 적용한다.
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
                // CANCEL-001 §6.5(V7): 취소 원장도 append-only 기관 금융 사실 원본.
                "GRANT SELECT, INSERT ON `paycore_simulator`.`van_cancel_entry` TO `paycore_simulator_runtime`@`%`",
                // WEBHOOK-001 §9(V9): 가상 가맹점 수신 원장 append-only.
                "GRANT SELECT, INSERT ON `paycore_simulator`.`merchant_webhook_receipt` TO `paycore_simulator_runtime`@`%`",
                // ACQUIRING-001 2-E(V12): 가상 매입사 기관 사실 append-only 4표 + Worker 진행 표(상태 열 4개만 UPDATE).
                "GRANT SELECT, INSERT ON `paycore_simulator`.`acquirer_received_file` TO `paycore_simulator_runtime`@`%`",
                "GRANT SELECT, INSERT ON `paycore_simulator`.`acquirer_record_decision` TO `paycore_simulator_runtime`@`%`",
                "GRANT SELECT, INSERT ON `paycore_simulator`.`acquirer_result_line` TO `paycore_simulator_runtime`@`%`",
                "GRANT SELECT, INSERT ON `paycore_simulator`.`acquirer_outbound_file` TO `paycore_simulator_runtime`@`%`",
                "GRANT SELECT, INSERT, UPDATE (`published_at`, `state`, `updated_at`, `version`) ON `paycore_simulator`.`acquirer_work_item` TO `paycore_simulator_runtime`@`%`",
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
                "UPDATE van_cancel_entry SET outcome = 'APPROVED' WHERE 1 = 0",
                "DELETE FROM van_cancel_entry WHERE 1 = 0",
                "UPDATE merchant_webhook_receipt SET outcome = 'ACCEPTED' WHERE 1 = 0",
                "DELETE FROM merchant_webhook_receipt WHERE 1 = 0",
                "UPDATE acquirer_received_file SET outcome = 'RECEIVED' WHERE 1 = 0",
                "DELETE FROM acquirer_received_file WHERE 1 = 0",
                "UPDATE acquirer_record_decision SET decision = 'ACCEPTED' WHERE 1 = 0",
                "DELETE FROM acquirer_record_decision WHERE 1 = 0",
                "UPDATE acquirer_result_line SET result = 'ACCEPTED' WHERE 1 = 0",
                "DELETE FROM acquirer_result_line WHERE 1 = 0",
                "UPDATE acquirer_outbound_file SET content_bytes = 1 WHERE 1 = 0",
                "DELETE FROM acquirer_outbound_file WHERE 1 = 0",
                "DELETE FROM acquirer_work_item WHERE 1 = 0",
            ).forEach { sql ->
                val e = shouldThrow<SQLException> { c.createStatement().use { it.executeUpdate(sql) } }
                e.errorCode shouldBe 1142
            }
            c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM van_ledger_entry").use { rs -> rs.next() shouldBe true } }
        }
    }

    // 검토 #2 n-1: 기동 검사는 lookup_log UPDATE/DELETE와 van_pending_decision DELETE도 본다.
    test("a table-level UPDATE on lookup_log or DELETE on van_pending_decision is refused at startup (review #2 n-1)") {
        listOf(
            "UPDATE ON paycore_simulator.lookup_log", "DELETE ON paycore_simulator.van_pending_decision",
            // CANCEL-001 §6.5: 취소 원장 UPDATE/DELETE도 기동 거부.
            "UPDATE ON paycore_simulator.van_cancel_entry", "DELETE ON paycore_simulator.van_cancel_entry",
            // WEBHOOK-001 §9: 가상 가맹점 수신 원장 UPDATE/DELETE도 기동 거부.
            "UPDATE ON paycore_simulator.merchant_webhook_receipt", "DELETE ON paycore_simulator.merchant_webhook_receipt",
            // ACQUIRING-001 2-E: 가상 매입사 append-only 원장 UPDATE/DELETE, 진행 표 식별 열 UPDATE·DELETE도 기동 거부.
            "UPDATE ON paycore_simulator.acquirer_received_file", "DELETE ON paycore_simulator.acquirer_record_decision",
            "UPDATE (file_name) ON paycore_simulator.acquirer_work_item", "DELETE ON paycore_simulator.acquirer_work_item",
            // 리뷰 m-5: 기동 검사는 허용 4열을 뺀 진행 표의 모든 열을 탐침한다(고정 탐침 2열 밖의 열도).
            "UPDATE (receipt_id) ON paycore_simulator.acquirer_work_item", "UPDATE (created_at) ON paycore_simulator.acquirer_work_item",
            // 재리뷰 m-4: append-only 4표도 information_schema의 모든 열을 탐침한다(고정 탐침 열 밖의 열 권한도 기동 거부).
            "UPDATE (accepted_evidence_entry_id) ON paycore_simulator.acquirer_record_decision", "UPDATE (content_bytes) ON paycore_simulator.acquirer_received_file",
        ).forEach { grant ->
            root().use { c -> c.createStatement().use { it.execute("GRANT $grant TO 'paycore_simulator_runtime'@'%'") } }
            try {
                shouldThrow<Exception> { start().close() }
            } finally {
                // 실패해도 권한을 되돌린다 — 뒤 시험이 이 권한 때문에 연쇄 실패하지 않게.
                root().use { c -> c.createStatement().use { it.execute("REVOKE $grant FROM 'paycore_simulator_runtime'@'%'") } }
            }
        }
        start().close()
    }

    // ACQUIRING-001 2-E: Worker 진행 표는 상태 열 4개만 runtime UPDATE(식별·계획 열 1143, DELETE 1142). 같은 매입사에서 같은 거래·같은 종류의 두 번째 ACCEPTED는
    // runtime 계정으로도 DB UNIQUE가 거부한다(1062 — 이중 매입 확정의 DB 방어). 시험 행은 rollback한다.
    test("acquirer work item: only state, published_at, updated_at and version are updatable; a second ACCEPTED of the same transaction is 1062") {
        runtime().use { c ->
            fun code(sql: String): Int? = try {
                c.createStatement().use { it.executeUpdate(sql) }
                null
            } catch (e: SQLException) {
                e.errorCode
            }
            val columns = c.createStatement().use { st ->
                st.executeQuery("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'acquirer_work_item'")
                    .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            val allowed = setOf("state", "published_at", "updated_at", "version")
            columns.containsAll(allowed + setOf("work_item_id", "receipt_id", "file_name", "due_at")) shouldBe true
            val codes = columns.associateWith { col -> code("UPDATE acquirer_work_item SET `$col` = `$col` WHERE work_item_id = -1") }
            codes.filterKeys { it in allowed } shouldBe allowed.associateWith { null }
            codes.filterKeys { it !in allowed }.values.toSet() shouldBe setOf(1143)
            code("DELETE FROM acquirer_work_item WHERE 1 = 0") shouldBe 1142

            c.autoCommit = false
            try {
                c.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO acquirer_received_file (acquirer_code, file_id, receipt_ordinal, content_sha256, content_bytes, record_count, outcome, reason_code, " +
                            "received_at) VALUES ('PRIVPROBE', 'ACQ.PRIVPROBE.TEST.20261105.001', 1, REPEAT('a', 64), 1, 2, 'RECEIVED', NULL, NOW(3))",
                    )
                }
                val receiptId = c.createStatement().use { st -> st.executeQuery("SELECT LAST_INSERT_ID()").use { rs -> rs.next(); rs.getLong(1) } }
                // 같은 거래 ID의 두 번째 ACCEPTED(근거 행은 서로 다름) → 거래 ID UNIQUE 1062.
                fun decide(seq: Int) = code(
                    "INSERT INTO acquirer_record_decision (receipt_id, record_seq, acquirer_code, record_type, transaction_id, provider_transaction_id, record_amount_minor, " +
                        "decision, reason_code, evidence_entry_id, scenario, accepted_transaction_id, authorization_entry_id, accepted_evidence_entry_id, " +
                        "netted_cancel_amount_minor, decided_at) VALUES ($receiptId, $seq, 'PRIVPROBE', 'SALE', 't-dup', 'VAN-T-dup', 1000, 'ACCEPTED', NULL, " +
                        "'ad_01JBV000000000000000000E$seq', NULL, 't-dup', 'ad_01JBV000000000000000000E$seq', 'ad_01JBV000000000000000000E$seq', 0, NOW(3))",
                )
                decide(1) shouldBe null
                decide(2) shouldBe 1062
                // 리뷰 MJ-1: 같은 기관 근거(VAN 원장 행)를 가리키는 두 번째 ACCEPTED는 거래 ID가 달라도 1062.
                fun decideOnEvidence(seq: Int, txn: String) = code(
                    "INSERT INTO acquirer_record_decision (receipt_id, record_seq, acquirer_code, record_type, transaction_id, provider_transaction_id, record_amount_minor, " +
                        "decision, reason_code, evidence_entry_id, scenario, accepted_transaction_id, authorization_entry_id, accepted_evidence_entry_id, " +
                        "netted_cancel_amount_minor, decided_at) VALUES ($receiptId, $seq, 'PRIVPROBE', 'SALE', '$txn', 'VAN-T-same', 1000, 'ACCEPTED', NULL, " +
                        "'ad_01JBV0000000000000000000E9', NULL, '$txn', 'ad_01JBV0000000000000000000E9', 'ad_01JBV0000000000000000000E9', 0, NOW(3))",
                )
                decideOnEvidence(3, "t-ev-1") shouldBe null
                decideOnEvidence(4, "t-ev-2") shouldBe 1062
            } finally {
                c.rollback()
                c.autoCommit = true
            }
        }
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
