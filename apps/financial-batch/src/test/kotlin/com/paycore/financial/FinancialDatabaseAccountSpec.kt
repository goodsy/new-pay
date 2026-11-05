package com.paycore.financial

import com.paycore.financial.FinancialDbAccounts.FINANCIAL_DATABASE
import com.paycore.financial.FinancialDbAccounts.GRANTOR
import com.paycore.financial.FinancialDbAccounts.INIT_SCRIPT_IN_CONTAINER
import com.paycore.financial.FinancialDbAccounts.MASTER_DATABASE
import com.paycore.financial.FinancialDbAccounts.MASTER_MIGRATION_USER
import com.paycore.financial.FinancialDbAccounts.MASTER_RUNTIME_USER
import com.paycore.financial.FinancialDbAccounts.MIGRATION_USER
import com.paycore.financial.FinancialDbAccounts.PAYMENT_DATABASE
import com.paycore.financial.FinancialDbAccounts.PAYMENT_MIGRATION_USER
import com.paycore.financial.FinancialDbAccounts.PAYMENT_RUNTIME_USER
import com.paycore.financial.FinancialDbAccounts.RUNTIME_ROLE
import com.paycore.financial.FinancialDbAccounts.RUNTIME_USER
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.application.PaymentFactInboxService
import com.paycore.financial.paymentfact.application.provided.ReceivePaymentFactResult
import com.paycore.financial.paymentfact.domain.HoldReason
import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import com.paycore.financial.paymentfact.domain.PaymentFactInboxOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection

/**
 * FINANCIAL DB 계정·표/열 단위 runtime 권한(FIN-DBACCOUNT-001, D-FFR-002 — payment-api DatabaseAccountSeparationSpec과 같은 수준).
 * 실제 MySQL 8.4.11에 로컬 개발 초기화 스크립트(docker/mysql/init/01-owned-databases.sh)를 그대로 넣고, 운영 migration(V1 Batch metadata + V2 결제 사실 +
 * V3 runtime 권한)을 migration 계정으로 적용한 결과를 runtime 계정(앱 pool)·migration 계정·root(관찰 전용)로 확인한다.
 *
 * 오류 코드: 1142 = 표 명령 권한 없음, 1143 = 열 권한 없음, 1044 = DB 접근 권한 없음, 1227 = 전역 권한 필요, 1370 = routine 실행 권한 없음,
 * 1644 = procedure SIGNAL(허용되지 않은 요청).
 */
@SpringBootTest
@Import(FinancialMySqlContainerConfig::class)
class FinancialDatabaseAccountSpec(
    private val service: PaymentFactInboxService,
    private val jdbc: JdbcTemplate,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val tableProcedure = "${FINANCIAL_DATABASE}_privilege.set_runtime_table_privileges"
    val columnProcedure = "${FINANCIAL_DATABASE}_privilege.grant_runtime_column_update"

    fun <T> app(block: (Connection) -> T): T = FinancialDbAccounts.connect(container, RUNTIME_USER).use(block)
    fun <T> migration(block: (Connection) -> T): T = FinancialDbAccounts.connect(container, MIGRATION_USER).use(block)
    fun <T> root(block: (Connection) -> T): T = FinancialDbAccounts.root(container).use(block)
    fun rootLong(sql: String, vararg args: Any?): Long = root { c -> (c.scalar(sql, *args) as Number).toLong() }

    /**
     * Spring Batch 6.0.5 JDBC DAO가 실제로 UPDATE하는 열(근거: spring-batch-core 6.0.5 JdbcJobExecutionDao.UPDATE_JOB_EXECUTION·JdbcStepExecutionDao.UPDATE_STEP_EXECUTION·
     * JdbcExecutionContextDao.UPDATE_*_EXECUTION_CONTEXT, spring-jdbc 7.0.9 MySQLMaxValueIncrementer `update <seq> set ID = last_insert_id(ID + ?) limit 1`).
     * 식별 열(*_ID·STEP_NAME·JOB_KEY·UNIQUE_KEY)은 UPDATE할 수 없다(1143).
     */
    val jobExecutionUpdatable = listOf("CREATE_TIME", "END_TIME", "EXIT_CODE", "EXIT_MESSAGE", "LAST_UPDATED", "START_TIME", "STATUS", "VERSION")
    val stepExecutionUpdatable = listOf(
        "COMMIT_COUNT", "END_TIME", "EXIT_CODE", "EXIT_MESSAGE", "FILTER_COUNT", "LAST_UPDATED", "PROCESS_SKIP_COUNT", "READ_COUNT",
        "READ_SKIP_COUNT", "ROLLBACK_COUNT", "START_TIME", "STATUS", "VERSION", "WRITE_COUNT", "WRITE_SKIP_COUNT",
    )
    val contextUpdatable = listOf("SERIALIZED_CONTEXT", "SHORT_CONTEXT")

    /** 열 단위 UPDATE를 받는 표 → 열(정렬 — SHOW GRANTS 표기 순). 결제별 직렬화 행은 반영 건수·갱신 시각·@Version만(신원 열은 첫 사실 값 고정). */
    val expectedColumnUpdates: Map<String, List<String>> = mapOf(
        "payment_fact_stream" to listOf("fact_count", "updated_at", "version"),
        "BATCH_JOB_EXECUTION" to jobExecutionUpdatable,
        "BATCH_STEP_EXECUTION" to stepExecutionUpdatable,
        "BATCH_JOB_EXECUTION_CONTEXT" to contextUpdatable,
        "BATCH_STEP_EXECUTION_CONTEXT" to contextUpdatable,
        "BATCH_JOB_INSTANCE_SEQ" to listOf("ID"),
        "BATCH_JOB_EXECUTION_SEQ" to listOf("ID"),
        "BATCH_STEP_EXECUTION_SEQ" to listOf("ID"),
    )

    /** 표 → 표 단위 권한(mysql.tables_priv Table_priv). append-only 사실 표 = SELECT, INSERT만. 순번 표는 행이 V1에서 하나 생기고 INSERT가 없다. DELETE는 없다. */
    val expectedTablePrivileges: Map<String, String> = mapOf(
        "payment_fact_inbox" to "Select,Insert",
        "payment_fact_authorization" to "Select,Insert",
        "payment_fact_cancellation" to "Select,Insert",
        "payment_fact_hold" to "Select,Insert",
        "payment_fact_stream" to "Select,Insert",
        "BATCH_JOB_INSTANCE" to "Select,Insert",
        "BATCH_JOB_EXECUTION" to "Select,Insert",
        "BATCH_JOB_EXECUTION_PARAMS" to "Select,Insert",
        "BATCH_STEP_EXECUTION" to "Select,Insert",
        "BATCH_JOB_EXECUTION_CONTEXT" to "Select,Insert",
        "BATCH_STEP_EXECUTION_CONTEXT" to "Select,Insert",
        "BATCH_JOB_INSTANCE_SEQ" to "Select",
        "BATCH_JOB_EXECUTION_SEQ" to "Select",
        "BATCH_STEP_EXECUTION_SEQ" to "Select",
    )

    fun grantLine(table: String): String {
        val privileges = expectedTablePrivileges.getValue(table).split(",").joinToString(", ") { it.uppercase() }
        val columns = expectedColumnUpdates[table]?.joinToString(", ", prefix = ", UPDATE (", postfix = ")") { "`$it`" } ?: ""
        return "GRANT $privileges$columns ON `$FINANCIAL_DATABASE`.`$table` TO `$RUNTIME_ROLE`@`%`"
    }

    val expectedAccountGrants: Map<String, Set<String>> = mapOf(
        RUNTIME_USER to setOf("GRANT USAGE ON *.* TO `$RUNTIME_USER`@`%`", "GRANT `$RUNTIME_ROLE`@`%` TO `$RUNTIME_USER`@`%`"),
        RUNTIME_ROLE to setOf("GRANT USAGE ON *.* TO `$RUNTIME_ROLE`@`%`") + expectedTablePrivileges.keys.map(::grantLine),
        MIGRATION_USER to setOf(
            "GRANT USAGE ON *.* TO `$MIGRATION_USER`@`%`",
            "GRANT ALL PRIVILEGES ON `$FINANCIAL_DATABASE`.* TO `$MIGRATION_USER`@`%`",
            "GRANT EXECUTE ON PROCEDURE `${FINANCIAL_DATABASE}_privilege`.`set_runtime_table_privileges` TO `$MIGRATION_USER`@`%`",
            "GRANT EXECUTE ON PROCEDURE `${FINANCIAL_DATABASE}_privilege`.`grant_runtime_column_update` TO `$MIGRATION_USER`@`%`",
        ),
        GRANTOR to setOf(
            "GRANT USAGE ON *.* TO `$GRANTOR`@`localhost`",
            "GRANT SELECT, INSERT, UPDATE, DELETE ON `$FINANCIAL_DATABASE`.* TO `$GRANTOR`@`localhost` WITH GRANT OPTION",
            "GRANT EXECUTE ON PROCEDURE `${FINANCIAL_DATABASE}_privilege`.`set_runtime_table_privileges` TO `$GRANTOR`@`localhost`",
            "GRANT EXECUTE ON PROCEDURE `${FINANCIAL_DATABASE}_privilege`.`grant_runtime_column_update` TO `$GRANTOR`@`localhost`",
        ),
    )

    fun grantsOf(account: String): Set<String> = root { c ->
        c.createStatement().use { s -> s.executeQuery("SHOW GRANTS FOR $account").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } } }
    }

    fun actualAccountGrants(): Map<String, Set<String>> = mapOf(
        RUNTIME_USER to grantsOf("'$RUNTIME_USER'@'%'"),
        RUNTIME_ROLE to grantsOf("'$RUNTIME_ROLE'@'%'"),
        MIGRATION_USER to grantsOf("'$MIGRATION_USER'@'%'"),
        GRANTOR to grantsOf("'$GRANTOR'@'localhost'"),
    )

    fun columnsOf(table: String): List<String> = root { c ->
        c.column("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION", FINANCIAL_DATABASE, table)
    }

    fun count(sql: String, vararg args: Any): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!

    Given("the application pool connects with the FINANCIAL runtime account") {
        Then("only the runtime role is active and the account has no database-level privileges") {
            jdbc.queryForObject("SELECT CURRENT_USER()", String::class.java) shouldBe "$RUNTIME_USER@%"
            jdbc.queryForObject("SELECT CURRENT_ROLE()", String::class.java) shouldBe "`$RUNTIME_ROLE`@`%`"
            rootLong("SELECT COUNT(*) FROM mysql.db WHERE User = ? AND Db = ?", RUNTIME_USER, FINANCIAL_DATABASE) shouldBe 0
        }

        Then("the business path (held early cancel, original authorization, later cancel, duplicate re-delivery) succeeds within its privileges") {
            val (auth, early) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
            val pid = auth.identity.paymentId
            val later = PaymentFactFixture.cancelOf(auth, 2_000, returnSequence = 2, cumulativeMinor = 5_000)
            val earlyEnvelope = PaymentFactEnvelopeFixture.inbound(early)
            listOf(
                service.receive(earlyEnvelope),
                service.receive(PaymentFactEnvelopeFixture.inbound(auth)),
                service.receive(PaymentFactEnvelopeFixture.inbound(later)),
                service.receive(earlyEnvelope),
            ) shouldBe listOf(
                ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.HELD, duplicate = false),
                ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false),
                ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false),
                ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.HELD, duplicate = true),
            )
            mapOf(
                "inbox" to count("SELECT COUNT(*) FROM payment_fact_inbox WHERE payment_id = ?", pid),
                "authorization" to count("SELECT COUNT(*) FROM payment_fact_authorization WHERE payment_id = ?", pid),
                "cancellation" to count("SELECT COUNT(*) FROM payment_fact_cancellation WHERE payment_id = ?", pid),
                "hold" to count("SELECT COUNT(*) FROM payment_fact_hold WHERE payment_id = ? AND reason = ?", pid, HoldReason.ORIGINAL_UNKNOWN.name),
                "factCount" to count("SELECT fact_count FROM payment_fact_stream WHERE payment_id = ?", pid),
                "version" to count("SELECT version FROM payment_fact_stream WHERE payment_id = ?", pid),
            ) shouldBe mapOf("inbox" to 3, "authorization" to 1, "cancellation" to 2, "hold" to 1, "factCount" to 3, "version" to 3)
        }

        Then("append-only fact tables (inbox, authorization, cancellation, hold) reject UPDATE, DELETE and lock reads with 1142 and the rows stay unchanged") {
            val (auth, early) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
            val pid = auth.identity.paymentId
            service.receive(PaymentFactEnvelopeFixture.inbound(early))
            service.receive(PaymentFactEnvelopeFixture.inbound(auth))
            val before = jdbc.queryForList(
                "SELECT (SELECT GROUP_CONCAT(outcome ORDER BY id) FROM payment_fact_inbox WHERE payment_id = ?) AS inbox, " +
                    "(SELECT amount_minor FROM payment_fact_authorization WHERE payment_id = ?) AS auth, " +
                    "(SELECT amount_minor FROM payment_fact_cancellation WHERE payment_id = ?) AS cancel, " +
                    "(SELECT GROUP_CONCAT(reason) FROM payment_fact_hold WHERE payment_id = ?) AS hold",
                pid, pid, pid, pid,
            ).single()
            app { c ->
                listOf("payment_fact_inbox", "payment_fact_authorization", "payment_fact_cancellation", "payment_fact_hold").flatMap { table ->
                    listOf(
                        "$table update" to c.codeOf("UPDATE $table SET payment_id = 'other' WHERE payment_id = '$pid'"),
                        "$table delete" to c.codeOf("DELETE FROM $table WHERE payment_id = '$pid'"),
                        "$table lock" to c.codeOf("SELECT * FROM $table WHERE payment_id = '$pid' FOR UPDATE"),
                        "$table truncate" to c.codeOf("TRUNCATE TABLE $table"),
                    )
                }.toMap()
            }.values.toSet() shouldBe setOf(1142)
            jdbc.queryForList(
                "SELECT (SELECT GROUP_CONCAT(outcome ORDER BY id) FROM payment_fact_inbox WHERE payment_id = ?) AS inbox, " +
                    "(SELECT amount_minor FROM payment_fact_authorization WHERE payment_id = ?) AS auth, " +
                    "(SELECT amount_minor FROM payment_fact_cancellation WHERE payment_id = ?) AS cancel, " +
                    "(SELECT GROUP_CONCAT(reason) FROM payment_fact_hold WHERE payment_id = ?) AS hold",
                pid, pid, pid, pid,
            ).single() shouldBe before
            before shouldBe mapOf("inbox" to "HELD,APPLIED", "auth" to auth.money.minor, "cancel" to early.money.minor, "hold" to HoldReason.ORIGINAL_UNKNOWN.name)
        }

        Then("payment_fact_stream: only fact_count, updated_at and version are updatable (identity columns 1143), DELETE is 1142, the IODKU slot and the lock read work") {
            val columns = columnsOf("payment_fact_stream")
            val allowed = expectedColumnUpdates.getValue("payment_fact_stream").toSet()
            columns.containsAll(allowed + setOf("payment_id", "environment", "merchant_account_id", "merchant_order_id", "created_at")) shouldBe true
            val codes = app { c -> columns.associateWith { col -> c.codeOf("UPDATE payment_fact_stream SET `$col` = `$col` WHERE payment_id = 'none'") } }
            codes.filterKeys { it in allowed } shouldBe allowed.associateWith { null }
            codes.filterKeys { it !in allowed }.values.toSet() shouldBe setOf(1143)
            val auth = PaymentFactFixture.authorized()
            val pid = auth.identity.paymentId
            service.receive(PaymentFactEnvelopeFixture.inbound(auth))
            val iodku = "INSERT INTO payment_fact_stream (payment_id, environment, merchant_account_id, merchant_site_id, merchant_order_id, fact_count, " +
                "created_at, updated_at, version) VALUES ('$pid', 'TEST', 'mid', 'site', 'order', 0, NOW(3), NOW(3), 0)"
            app { c ->
                mapOf(
                    "delete" to c.codeOf("DELETE FROM payment_fact_stream WHERE payment_id = '$pid'"),
                    // 운영 코드의 자리 확보(PaymentFactStreamJpaRepository.insertIfAbsent)와 같은 형태: INSERT + 열 단위 UPDATE(version) 둘 다 필요.
                    "iodku slot" to c.codeOf("$iodku ON DUPLICATE KEY UPDATE version = version"),
                    "iodku identity" to c.codeOf("$iodku ON DUPLICATE KEY UPDATE merchant_order_id = 'changed'"),
                    "lock read" to c.codeOf("SELECT payment_id FROM payment_fact_stream WHERE payment_id = '$pid' FOR UPDATE"),
                )
            } shouldBe mapOf("delete" to 1142, "iodku slot" to null, "iodku identity" to 1143, "lock read" to null)
            jdbc.queryForMap("SELECT merchant_order_id, fact_count, version FROM payment_fact_stream WHERE payment_id = ?", pid) shouldBe mapOf(
                "merchant_order_id" to auth.identity.merchantOrderId, "fact_count" to 1, "version" to 1L,
            )
        }

        Then("Spring Batch metadata: no DELETE anywhere, UPDATE only on the columns the Batch 6.0.5 JDBC DAOs write, no INSERT into the sequence tables") {
            val batchTables = expectedTablePrivileges.keys.filter { it.startsWith("BATCH_") }
            val observed = app { c ->
                batchTables.associateWith { table ->
                    val updates = columnsOf(table).associateWith { col -> c.codeOf("UPDATE `$table` SET `$col` = `$col` WHERE 1 = 0") }
                    mapOf(
                        "delete" to c.codeOf("DELETE FROM `$table` WHERE 1 = 0"),
                        "updatable" to updates.filterValues { it == null }.keys.sorted(),
                        "denied" to updates.filterValues { it != null }.values.toSet(),
                    )
                }
            }
            observed shouldBe batchTables.associateWith { table ->
                val updatable = expectedColumnUpdates[table].orEmpty()
                mapOf(
                    "delete" to 1142,
                    "updatable" to updatable,
                    // 열 권한이 하나도 없는 표는 표 명령 거부(1142), 일부 열만 있는 표의 나머지 열은 열 거부(1143).
                    "denied" to if (updatable.isEmpty()) setOf(1142) else setOf(1143),
                )
            }
            app { c ->
                listOf("BATCH_JOB_INSTANCE_SEQ", "BATCH_JOB_EXECUTION_SEQ", "BATCH_STEP_EXECUTION_SEQ").associateWith { seq ->
                    c.codeOf("INSERT INTO `$seq` (ID, UNIQUE_KEY) VALUES (0, 'x')")
                }
            }.values.toSet() shouldBe setOf(1142)
        }

        Then("it cannot change the schema, read the Flyway history or call the privilege procedures") {
            app { c ->
                mapOf(
                    "create" to c.codeOf("CREATE TABLE sneaky (id INT PRIMARY KEY)"),
                    "alter" to c.codeOf("ALTER TABLE payment_fact_inbox ADD COLUMN sneaky INT"),
                    "drop" to c.codeOf("DROP TABLE payment_fact_hold"),
                    "truncate batch" to c.codeOf("TRUNCATE TABLE BATCH_JOB_INSTANCE"),
                    "history" to c.codeOf("SELECT COUNT(*) FROM flyway_schema_history"),
                    "table procedure" to c.codeOf("CALL $tableProcedure('payment_fact_inbox', 'SELECT, INSERT, UPDATE, DELETE')"),
                    "column procedure" to c.codeOf("CALL $columnProcedure('payment_fact_inbox', 'outcome')"),
                )
            } shouldBe mapOf(
                "create" to 1142, "alter" to 1142, "drop" to 1142, "truncate batch" to 1142, "history" to 1142, "table procedure" to 1370, "column procedure" to 1370,
            )
        }

        Then("it cannot read other owned databases (no cross-DB access)") {
            app { c ->
                c.codeOf("SELECT COUNT(*) FROM $PAYMENT_DATABASE.flyway_schema_history")!! shouldBeIn setOf(1142, 1044)
                c.codeOf("SELECT COUNT(*) FROM $MASTER_DATABASE.flyway_schema_history")!! shouldBeIn setOf(1142, 1044)
                c.codeOf("USE $PAYMENT_DATABASE") shouldBe 1044
            }
        }
    }

    Given("the FINANCIAL migration account (Flyway)") {
        Then("Flyway installed V1 (Batch metadata), V2 (payment facts) and V3 (runtime privileges) as the migration account") {
            root { c ->
                mapOf(
                    "installedBy" to c.column("SELECT DISTINCT installed_by FROM flyway_schema_history").toSet(),
                    "versions" to c.column("SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank"),
                )
            } shouldBe mapOf("installedBy" to setOf(MIGRATION_USER), "versions" to listOf("1", "2", "3"))
        }

        Then("it cannot grant privileges to any account directly, grant the runtime role, or create accounts") {
            migration { c ->
                mapOf(
                    "to master runtime" to c.codeOf("GRANT SELECT ON payment_fact_inbox TO '$MASTER_RUNTIME_USER'@'%'"),
                    "to financial runtime account" to c.codeOf("GRANT UPDATE, DELETE ON payment_fact_hold TO '$RUNTIME_USER'@'%'"),
                    "to runtime role" to c.codeOf("GRANT DELETE ON payment_fact_inbox TO '$RUNTIME_ROLE'@'%'"),
                    "role to outside" to c.codeOf("GRANT '$RUNTIME_ROLE'@'%' TO '$PAYMENT_RUNTIME_USER'@'%'"),
                    "create user" to c.codeOf("CREATE USER 'paycore_outside'@'%' IDENTIFIED BY 'x'"),
                )
            } shouldBe mapOf(
                "to master runtime" to 1142, "to financial runtime account" to 1142, "to runtime role" to 1142, "role to outside" to 1227, "create user" to 1227,
            )
            migration { c -> c.codeOf("GRANT SELECT ON $FINANCIAL_DATABASE.* TO '$MASTER_RUNTIME_USER'@'%'")!! shouldBeIn setOf(1044, 1142) }
            grantsOf("'$MASTER_RUNTIME_USER'@'%'").none { it.contains(FINANCIAL_DATABASE) } shouldBe true
        }

        Then("the table procedure only accepts the allowed privilege sets on base tables of this database (lower-case names or BATCH_ upper-case names)") {
            val refused = migration { c ->
                c.codeOf("CREATE VIEW tech_privilege_view AS SELECT payment_id FROM payment_fact_inbox") shouldBe null
                try {
                    mapOf(
                        "extra privilege" to c.codeOf("CALL $tableProcedure('payment_fact_inbox', 'SELECT, INSERT, UPDATE, DELETE, DROP')"),
                        "grantee injection" to c.codeOf("CALL $tableProcedure('payment_fact_inbox', 'SELECT ON *.* TO paycore_master; --')"),
                        "lower case set" to c.codeOf("CALL $tableProcedure('payment_fact_inbox', 'select')"),
                        "flyway history" to c.codeOf("CALL $tableProcedure('flyway_schema_history', 'SELECT')"),
                        "missing table" to c.codeOf("CALL $tableProcedure('no_such_table', 'SELECT')"),
                        "view" to c.codeOf("CALL $tableProcedure('tech_privilege_view', 'SELECT')"),
                        "quoted name" to c.codeOf("CALL $tableProcedure('payment_fact_inbox` TO paycore_master; --', 'SELECT')"),
                        "trailing newline" to c.codeOf("CALL $tableProcedure('payment_fact_inbox\n', 'SELECT')"),
                        "carriage return" to c.codeOf("CALL $tableProcedure('payment_fact\rinbox', 'SELECT')"),
                        "upper case non-batch" to c.codeOf("CALL $tableProcedure('PAYMENT_FACT_INBOX', 'SELECT')"),
                        "mixed case batch" to c.codeOf("CALL $tableProcedure('Batch_job_instance', 'SELECT')"),
                        "lower case batch" to c.codeOf("CALL $tableProcedure('batch_job_instance', 'SELECT')"),
                    )
                } finally {
                    c.codeOf("DROP VIEW tech_privilege_view") shouldBe null
                }
            }
            refused.values.toSet() shouldBe setOf(1644)
            refused.size shouldBe 12
            grantsOf("'$RUNTIME_ROLE'@'%'") shouldBe expectedAccountGrants.getValue(RUNTIME_ROLE)
        }

        Then("the column procedure grants UPDATE only on existing columns of a base table, with validated names") {
            val refused = migration { c ->
                mapOf(
                    "missing column" to c.codeOf("CALL $columnProcedure('payment_fact_stream', 'no_such_column')"),
                    "one missing of two" to c.codeOf("CALL $columnProcedure('payment_fact_stream', 'fact_count, no_such_column')"),
                    "injection" to c.codeOf("CALL $columnProcedure('payment_fact_stream', 'fact_count) ON *.* TO paycore_master; --')"),
                    "missing table" to c.codeOf("CALL $columnProcedure('no_such_table', 'id')"),
                    "flyway history" to c.codeOf("CALL $columnProcedure('flyway_schema_history', 'version')"),
                    "mixed case column" to c.codeOf("CALL $columnProcedure('payment_fact_stream', 'Fact_count')"),
                    "nine columns" to c.codeOf("CALL $columnProcedure('BATCH_STEP_EXECUTION', '${stepExecutionUpdatable.take(9).joinToString(", ")}')"),
                    "empty" to c.codeOf("CALL $columnProcedure('payment_fact_stream', '')"),
                )
            }
            refused.values.toSet() shouldBe setOf(1644)
            // 재실행 안전(멱등): 같은 열을 다시 주어도 권한 줄은 그대로다.
            migration { c -> c.codeOf("CALL $columnProcedure('payment_fact_stream', 'fact_count, updated_at, version')") } shouldBe null
            grantsOf("'$RUNTIME_ROLE'@'%'") shouldBe expectedAccountGrants.getValue(RUNTIME_ROLE)
        }

        Then("the procedures replace a table's runtime privileges with exactly the given set, for lower-case and BATCH_ tables") {
            fun privilegeOf(table: String): String? = root { c ->
                c.scalar(
                    "SELECT CONCAT(Table_priv, '|', Column_priv) FROM mysql.tables_priv WHERE User = ? AND Host = '%' AND Db = ? AND Table_name = ?",
                    RUNTIME_ROLE, FINANCIAL_DATABASE, table,
                )?.toString()
            }
            for (table in listOf("tech_privilege_probe", "BATCH_TECH_PROBE")) {
                migration { c ->
                    c.codeOf("CREATE TABLE `$table` (ID INT PRIMARY KEY, NOTE INT)") shouldBe null
                    try {
                        listOf("SELECT, INSERT, UPDATE, DELETE", "SELECT", "SELECT, INSERT").map { set ->
                            c.codeOf("CALL $tableProcedure('$table', '$set')") shouldBe null
                            set to privilegeOf(table)
                        } shouldBe listOf(
                            "SELECT, INSERT, UPDATE, DELETE" to "Select,Insert,Update,Delete|",
                            "SELECT" to "Select|",
                            "SELECT, INSERT" to "Select,Insert|",
                        )
                        c.codeOf("CALL $columnProcedure('$table', 'NOTE')") shouldBe null
                        privilegeOf(table) shouldBe "Select,Insert|Update"
                    } finally {
                        // DROP TABLE은 표·열 단위 권한 행을 지우지 않는다(MySQL). 시험 표의 권한은 root가 지운다.
                        root { r -> r.codeOf("REVOKE IF EXISTS ALL PRIVILEGES ON $FINANCIAL_DATABASE.`$table` FROM '$RUNTIME_ROLE'@'%'") }
                        c.codeOf("DROP TABLE `$table`") shouldBe null
                    }
                }
                privilegeOf(table) shouldBe null
            }
        }

        Then("it cannot use other owned databases or their privilege procedures, nor change its own privilege procedures") {
            migration { c ->
                mapOf(
                    "use master" to c.codeOf("USE $MASTER_DATABASE"),
                    "use payment" to c.codeOf("USE $PAYMENT_DATABASE"),
                    "payment procedure" to c.codeOf("CALL ${PAYMENT_DATABASE}_privilege.set_runtime_table_privileges('x', 'SELECT')"),
                    "drop procedure" to c.codeOf("DROP PROCEDURE $tableProcedure"),
                    "drop column procedure" to c.codeOf("DROP PROCEDURE $columnProcedure"),
                    "privilege schema table" to c.codeOf("CREATE TABLE ${FINANCIAL_DATABASE}_privilege.sneaky (id INT)"),
                )
            } shouldBe mapOf(
                "use master" to 1044, "use payment" to 1044, "payment procedure" to 1370, "drop procedure" to 1370, "drop column procedure" to 1370,
                "privilege schema table" to 1142,
            )
        }
    }

    Given("the resulting FINANCIAL accounts and privileges") {
        Then("SHOW GRANTS is exact: runtime account = role only, role = table/column grants, migration = DB ALL + procedure EXECUTE, grantor locked") {
            actualAccountGrants() shouldBe expectedAccountGrants
            root { c ->
                c.column(
                    "SELECT CONCAT(User, '=', account_locked) FROM mysql.user WHERE User IN (?, ?, ?, ?) ORDER BY User",
                    RUNTIME_USER, RUNTIME_ROLE, MIGRATION_USER, GRANTOR,
                ).toSet()
            } shouldBe setOf("$RUNTIME_USER=N", "$GRANTOR=Y", "$MIGRATION_USER=N", "$RUNTIME_ROLE=Y")
        }

        Then("every FINANCIAL base table has an explicit runtime grant (a new table without a privilege migration fails here)") {
            root { c ->
                mapOf(
                    "tables" to c.column(
                        "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME <> 'flyway_schema_history'",
                        FINANCIAL_DATABASE,
                    ).toSet(),
                    "tablePrivileges" to c.column(
                        "SELECT CONCAT(Table_name, '=', Table_priv) FROM mysql.tables_priv WHERE User = ? AND Host = '%' AND Db = ?", RUNTIME_ROLE, FINANCIAL_DATABASE,
                    ).toSet(),
                    "columnPrivileges" to c.column(
                        "SELECT CONCAT(Table_name, '.', Column_name, '=', Column_priv) FROM mysql.columns_priv WHERE User = ? AND Host = '%' AND Db = ?",
                        RUNTIME_ROLE, FINANCIAL_DATABASE,
                    ).toSet(),
                )
            } shouldBe mapOf(
                "tables" to expectedTablePrivileges.keys,
                "tablePrivileges" to expectedTablePrivileges.map { (t, p) -> "$t=$p" }.toSet(),
                "columnPrivileges" to expectedColumnUpdates.flatMap { (t, cols) -> cols.map { "$t.$it=Update" } }.toSet(),
            )
        }

        Then("no triggers, routines, events or views exist in the FINANCIAL database (any future use needs a reviewed expectation change)") {
            listOf("TRIGGERS" to "TRIGGER_SCHEMA", "ROUTINES" to "ROUTINE_SCHEMA", "EVENTS" to "EVENT_SCHEMA", "VIEWS" to "TABLE_SCHEMA").associate { (view, column) ->
                view to rootLong("SELECT COUNT(*) FROM information_schema.$view WHERE $column = ?", FINANCIAL_DATABASE)
            } shouldBe mapOf("TRIGGERS" to 0L, "ROUTINES" to 0L, "EVENTS" to 0L, "VIEWS" to 0L)
        }

        Then("MASTER and PAYMENT accounts on the same server have no FINANCIAL privilege") {
            listOf(MASTER_RUNTIME_USER, MASTER_MIGRATION_USER, PAYMENT_RUNTIME_USER, PAYMENT_MIGRATION_USER)
                .flatMap { grantsOf("'$it'@'%'") }
                .filter { it.contains(FINANCIAL_DATABASE) } shouldBe emptyList()
        }
    }

    Given("a volume created before FIN-DBACCOUNT-001 (single FINANCIAL account with ALL on its DB WITH GRANT OPTION) upgraded by re-running the init script") {
        Then("the runtime account ends with no database-level privilege, the migration account without GRANT OPTION, the grantor locked, table/column grants kept") {
            root { r ->
                r.codeOf("GRANT ALL PRIVILEGES ON $FINANCIAL_DATABASE.* TO '$RUNTIME_USER'@'%' WITH GRANT OPTION") shouldBe null
                r.codeOf("GRANT ALL PRIVILEGES ON $FINANCIAL_DATABASE.* TO '$MIGRATION_USER'@'%' WITH GRANT OPTION") shouldBe null
                r.codeOf("ALTER USER '$GRANTOR'@'localhost' ACCOUNT UNLOCK") shouldBe null
            }
            // 사전 상태가 실제로 위험한지(대조): runtime 계정이 DB 단위 ALL로 append-only 표를 지울 수 있다.
            app { c -> c.codeOf("DELETE FROM payment_fact_inbox WHERE payment_id = 'none'") } shouldBe null

            val rerun = container.execInContainer("bash", INIT_SCRIPT_IN_CONTAINER)
            rerun.exitCode shouldBe 0

            mapOf(
                "runtime db rows" to rootLong("SELECT COUNT(*) FROM mysql.db WHERE User = ? AND Db = ?", RUNTIME_USER, FINANCIAL_DATABASE),
                "grant option db rows" to rootLong(
                    "SELECT COUNT(*) FROM mysql.db WHERE User IN (?, ?) AND Db = ? AND Grant_priv = 'Y'", RUNTIME_USER, MIGRATION_USER, FINANCIAL_DATABASE,
                ),
                "unlocked grantor" to rootLong("SELECT COUNT(*) FROM mysql.user WHERE User = ? AND account_locked <> 'Y'", GRANTOR),
            ) shouldBe mapOf("runtime db rows" to 0L, "grant option db rows" to 0L, "unlocked grantor" to 0L)
            actualAccountGrants() shouldBe expectedAccountGrants
            app { c ->
                mapOf(
                    "inbox delete" to c.codeOf("DELETE FROM payment_fact_inbox WHERE payment_id = 'none'"),
                    "hold drop" to c.codeOf("DROP TABLE payment_fact_hold"),
                )
            } shouldBe mapOf("inbox delete" to 1142, "hold drop" to 1142)
        }

        Then("re-running without PAYCORE_FINANCIAL_DB_MIGRATION_PASSWORD on a volume where FINANCIAL is in use fails and changes nothing") {
            val rerun = container.execInContainer("env", "-u", "PAYCORE_FINANCIAL_DB_MIGRATION_PASSWORD", "bash", INIT_SCRIPT_IN_CONTAINER)
            rerun.exitCode shouldBe 1
            rerun.stderr shouldContain "PAYCORE_FINANCIAL_DB_MIGRATION_PASSWORD not set but paycore_financial is already in use"
            actualAccountGrants() shouldBe expectedAccountGrants
        }
    }
})
