package com.paycore.admin

import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbAccounts.ADMIN_DATABASE
import com.paycore.admin.support.AdminDbAccounts.ADMIN_GRANTOR
import com.paycore.admin.support.AdminDbAccounts.ADMIN_MIGRATION_USER
import com.paycore.admin.support.AdminDbAccounts.ADMIN_RUNTIME_ROLE
import com.paycore.admin.support.AdminDbAccounts.ADMIN_RUNTIME_USER
import com.paycore.admin.support.AdminFreshInitDbTest
import com.paycore.admin.support.column
import com.paycore.admin.support.errorCodeOf
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection

/**
 * ADMIN DB 계정·권한(ADMIN-BOOT-001 AC1, §4.2 — D-ADMIN-005 ADMIN DB 소유). 로컬 초기화 스크립트(docker/mysql/init/01-owned-databases.sh)를
 * 그대로 넣은 MySQL 8.4.11과 운영 migration 위치(db/migration/admin + db/runtime-privilege/admin)로 확인한다.
 *
 * - runtime 계정 `paycore_admin`은 role `paycore_admin_runtime`만 가진다(DB 단위 권한 없음). role은 표 단위 SELECT·INSERT(+ 세션 표만 전체 DML)와
 *   **열 단위 UPDATE**(생성 불변 열 제외)만 받는다 — append-only 표는 UPDATE·DELETE 1142, 불변 열 UPDATE 1143, 계정 DELETE 1142(actor ID 재사용 방지).
 * - S0 m-4 실측 고정: 열 단위 UPDATE만 있는 표에서 `SELECT … FOR UPDATE`가 허용되고(잠금 경로 성립), SELECT·INSERT만 있는 표에서는 1142.
 * - migration 계정은 GRANT OPTION이 없고 권한은 procedure(받는 쪽 = 이 DB runtime role 고정, DEFINER = 잠긴 grantor)로만 준다.
 * - 초기화 스크립트의 ADMIN 블록은 ADMIN 비밀번호 두 개가 모두 있을 때만 실행된다(하나만 있으면 실패, 둘 다 없으면 생략 — 명세 §19 D-55).
 * - TEST-PERF-002: 초기화 사본이 아니라 빈 MySQL에서 초기화 스크립트를 이번 실행에 실제로 돌린 서버로 본다([AdminFreshInitDbTest], D-TEST-PERF-002).
 */
@SpringBootTest
@AdminFreshInitDbTest
class AdminDatabaseAccountSpec(private val container: MySQLContainer) : BehaviorSpec({

    fun root(): Connection = AdminDbAccounts.root(container)
    fun runtime(): Connection = AdminDbAccounts.connect(container, ADMIN_RUNTIME_USER)
    fun migration(): Connection = AdminDbAccounts.connect(container, ADMIN_MIGRATION_USER)

    fun grantsOf(user: String, host: String = "%"): Set<String> = root().use { c ->
        c.column("SHOW GRANTS FOR '$user'@'$host'").filterNot { it.startsWith("GRANT USAGE ON *.*") }.toSet()
    }

    fun tablePrivileges(): Map<String, String> = root().use { c ->
        c.column(
            "SELECT CONCAT(TABLE_NAME, '=', GROUP_CONCAT(PRIVILEGE_TYPE ORDER BY FIELD(PRIVILEGE_TYPE, 'SELECT', 'INSERT', 'UPDATE', 'DELETE') SEPARATOR ', ')) " +
                "FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE = ? AND TABLE_SCHEMA = ? GROUP BY TABLE_NAME ORDER BY TABLE_NAME",
            "'$ADMIN_RUNTIME_ROLE'@'%'", ADMIN_DATABASE,
        ).associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    fun columnPrivileges(): Map<String, Set<String>> = root().use { c ->
        c.column(
            "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME, '.', PRIVILEGE_TYPE) FROM information_schema.COLUMN_PRIVILEGES " +
                "WHERE GRANTEE = ? AND TABLE_SCHEMA = ?",
            "'$ADMIN_RUNTIME_ROLE'@'%'", ADMIN_DATABASE,
        ).groupBy({ it.substringBefore('.') }, { it.substringAfter('.') }).mapValues { it.value.toSet() }
    }

    fun execScript(vararg unset: String): Pair<Int, String> {
        val argv = arrayOf("env") + unset.flatMap { listOf("-u", it) } + arrayOf("bash", AdminDbAccounts.INIT_SCRIPT_IN_CONTAINER)
        val result = container.execInContainer(*argv)
        return result.exitCode to (result.stdout + result.stderr)
    }

    Given("the local init script (docker/mysql/init/01-owned-databases.sh) with both ADMIN passwords") {
        Then("ADMIN accounts: role-only runtime account, migration account (DB ALL + both procedures EXECUTE), locked grantor") {
            mapOf(
                ADMIN_RUNTIME_USER to grantsOf(ADMIN_RUNTIME_USER),
                ADMIN_MIGRATION_USER to grantsOf(ADMIN_MIGRATION_USER),
                ADMIN_GRANTOR to grantsOf(ADMIN_GRANTOR, "localhost"),
            ) shouldBe mapOf(
                "paycore_admin" to setOf("GRANT `paycore_admin_runtime`@`%` TO `paycore_admin`@`%`"),
                "paycore_admin_migration" to setOf(
                    "GRANT ALL PRIVILEGES ON `paycore_admin`.* TO `paycore_admin_migration`@`%`",
                    "GRANT EXECUTE ON PROCEDURE `paycore_admin_privilege`.`set_runtime_table_privileges` TO `paycore_admin_migration`@`%`",
                    "GRANT EXECUTE ON PROCEDURE `paycore_admin_privilege`.`grant_runtime_column_update` TO `paycore_admin_migration`@`%`",
                ),
                "paycore_admin_grantor" to setOf(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON `paycore_admin`.* TO `paycore_admin_grantor`@`localhost` WITH GRANT OPTION",
                    "GRANT EXECUTE ON PROCEDURE `paycore_admin_privilege`.`set_runtime_table_privileges` TO `paycore_admin_grantor`@`localhost`",
                    "GRANT EXECUTE ON PROCEDURE `paycore_admin_privilege`.`grant_runtime_column_update` TO `paycore_admin_grantor`@`localhost`",
                ),
            )
        }

        Then("the ADMIN runtime account activates its role by default; the role and the grantor cannot log in") {
            root().use { c ->
                mapOf(
                    "default role" to c.column("SELECT DEFAULT_ROLE_USER FROM mysql.default_roles WHERE USER = 'paycore_admin'"),
                    "locked" to c.column(
                        "SELECT CONCAT(User, '@', Host) FROM mysql.user WHERE User LIKE 'paycore\\_admin%' AND account_locked = 'Y' ORDER BY User",
                    ),
                )
            } shouldBe mapOf(
                "default role" to listOf(ADMIN_RUNTIME_ROLE),
                "locked" to listOf("paycore_admin_grantor@localhost", "paycore_admin_runtime@%"),
            )
        }

        Then("table-level runtime privileges are exactly SELECT, INSERT (append-only and column-updatable tables) and full DML only on the session tables") {
            tablePrivileges() shouldBe ADMIN_RUNTIME_TABLE_PRIVILEGES
        }

        Then("column-level UPDATE privileges are exactly the mutable columns (creation-immutable columns excluded)") {
            columnPrivileges() shouldBe ADMIN_RUNTIME_COLUMN_UPDATES.mapValues { (_, columns) -> columns.map { "$it.UPDATE" }.toSet() }
        }

        Then("every ADMIN table has a runtime privilege (a new table without one fails here)") {
            root().use { c ->
                val tables = c.column(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE' " +
                        "AND table_name <> 'flyway_schema_history'",
                    ADMIN_DATABASE,
                ).toSet()
                val granted = c.column(
                    "SELECT Table_name FROM mysql.tables_priv WHERE Db = ? AND User = ?", ADMIN_DATABASE, ADMIN_RUNTIME_ROLE,
                ).toSet()
                tables - granted
            } shouldBe emptySet()
        }

        Then("the ADMIN runtime role has no privilege outside paycore_admin") {
            root().use { c ->
                c.column("SELECT DISTINCT TABLE_SCHEMA FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE = ?", "'$ADMIN_RUNTIME_ROLE'@'%'") +
                    c.column("SELECT DISTINCT TABLE_SCHEMA FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE = ?", "'$ADMIN_RUNTIME_ROLE'@'%'")
            }.toSet() shouldBe setOf(ADMIN_DATABASE)
        }

        Then("no triggers, routines, events or views exist in the ADMIN database") {
            root().use { c ->
                listOf("TRIGGERS" to "TRIGGER_SCHEMA", "ROUTINES" to "ROUTINE_SCHEMA", "EVENTS" to "EVENT_SCHEMA", "VIEWS" to "TABLE_SCHEMA")
                    .associate { (view, column) -> view to c.column("SELECT COUNT(*) FROM information_schema.$view WHERE $column = ?", ADMIN_DATABASE).single() }
            } shouldBe mapOf("TRIGGERS" to "0", "ROUTINES" to "0", "EVENTS" to "0", "VIEWS" to "0")
        }

        Then("re-running the script is safe: it succeeds and keeps the table and column privileges") {
            val before = tablePrivileges() to columnPrivileges()
            val (exit, output) = execScript()
            (exit to output.contains("is not set")) shouldBe (0 to false)
            (tablePrivileges() to columnPrivileges()) shouldBe before
        }

        Then("only one ADMIN password set: the script fails (no half-configured ADMIN accounts)") {
            val (exit, output) = execScript("PAYCORE_ADMIN_DB_MIGRATION_PASSWORD")
            exit shouldBe 1
            output shouldContain "PAYCORE_ADMIN_DB_MIGRATION_PASSWORD"
            val (exitOther, _) = execScript("PAYCORE_ADMIN_DB_PASSWORD")
            exitOther shouldBe 1
        }

        Then("no ADMIN password but ADMIN already exists (an existing volume converged without the variables): the script fails") {
            // S2 리뷰 m-2: 기존 volume 수렴 실행에서 변수를 빠뜨리면 건너뛰지 않고 실패해야 한다(조용한 반쪽 수렴 금지).
            val (exit, output) = execScript("PAYCORE_ADMIN_DB_PASSWORD", "PAYCORE_ADMIN_DB_MIGRATION_PASSWORD")
            exit shouldBe 1
            output shouldContain ADMIN_EXISTS_MESSAGE
            output shouldNotContain ADMIN_SKIP_MESSAGE
        }
    }

    Given("a fresh MySQL without ADMIN (the condition of other modules' test containers)") {
        Then("the script skips ADMIN with the exact notice and leaves the ADMIN state unchanged (no schema, no accounts, no grants)") {
            AdminDbAccounts.newContainer(withAdmin = false).use { fresh ->
                fresh.start()
                fun adminState(): List<String> = AdminDbAccounts.root(fresh, database = "mysql").use { c ->
                    c.column("SELECT CONCAT('schema:', SCHEMA_NAME) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'paycore\\_admin%'") +
                        c.column("SELECT CONCAT('user:', User, '@', Host) FROM mysql.user WHERE User LIKE 'paycore\\_admin%' ORDER BY User") +
                        c.column("SELECT CONCAT('grant:', User, ':', Db, ':', Table_name) FROM mysql.tables_priv WHERE Db LIKE 'paycore\\_admin%'") +
                        c.column("SELECT CONCAT('procs:', Db, ':', Routine_name) FROM mysql.procs_priv WHERE Db LIKE 'paycore\\_admin%'")
                }
                val before = adminState()
                val result = fresh.execInContainer("bash", AdminDbAccounts.INIT_SCRIPT_IN_CONTAINER)
                (result.exitCode to result.stderr.lines().filter { it.contains("ADMIN") }) shouldBe (0 to listOf(ADMIN_SKIP_MESSAGE))
                adminState() shouldBe before
                before shouldBe emptyList()
            }
        }
    }

    Given("the application runtime account (paycore_admin)") {
        Then("append-only tables refuse UPDATE and DELETE with 1142") {
            runtime().use { c ->
                APPEND_ONLY_TABLES.associateWith { t ->
                    listOf(c.errorCodeOf("UPDATE $t SET ${firstColumn(t)} = ${firstColumn(t)}"), c.errorCodeOf("DELETE FROM $t"))
                }
            } shouldBe APPEND_ONLY_TABLES.associateWith { listOf(1142, 1142) }
        }

        Then("column-updatable tables refuse DELETE with 1142 (no row deletion — actor IDs are never reused)") {
            runtime().use { c -> ADMIN_RUNTIME_COLUMN_UPDATES.keys.associateWith { c.errorCodeOf("DELETE FROM $it") } } shouldBe
                ADMIN_RUNTIME_COLUMN_UPDATES.keys.associateWith { 1142 }
        }

        Then("creation-immutable columns refuse UPDATE with 1143") {
            runtime().use { c ->
                IMMUTABLE_COLUMNS.flatMap { (table, columns) -> columns.map { "$table.$it" to c.errorCodeOf("UPDATE $table SET $it = $it") } }.toMap()
            } shouldBe IMMUTABLE_COLUMNS.flatMap { (table, columns) -> columns.map { "$table.$it" to 1143 } }.toMap()
        }

        Then("V6: the reconciliation checkpoint takes no INSERT or DELETE from the runtime (single row created by V5 — 1142), only its column UPDATE") {
            runtime().use { c ->
                listOf(
                    c.errorCodeOf("INSERT INTO admin_ops_reconciliation_checkpoint (id, watermark, version) VALUES (1, 0, 0)"),
                    c.errorCodeOf("DELETE FROM admin_ops_reconciliation_checkpoint"),
                    c.errorCodeOf("UPDATE admin_ops_reconciliation_checkpoint SET watermark = watermark, last_run_at = last_run_at WHERE id = 1"),
                )
            } shouldBe listOf(1142, 1142, null)
        }

        Then("V6: the stored execution body and the command's pinned values cannot be rewritten by the runtime (1143 / 1142 — I-16)") {
            runtime().use { c ->
                mapOf(
                    "execution request_body" to c.errorCodeOf("UPDATE admin_owner_execution SET request_body = request_body"),
                    "execution fingerprint" to c.errorCodeOf("UPDATE admin_owner_execution SET fingerprint = fingerprint"),
                    "command pinned_target" to c.errorCodeOf("UPDATE admin_operator_cancel_command SET pinned_target = pinned_target"),
                    "command amount" to c.errorCodeOf("UPDATE admin_operator_cancel_command SET amount_minor = amount_minor"),
                    "approval target_ref" to c.errorCodeOf("UPDATE admin_approval_request SET target_ref = target_ref"),
                )
            } shouldBe mapOf(
                "execution request_body" to 1143, "execution fingerprint" to 1143, "command pinned_target" to 1142, "command amount" to 1142, "approval target_ref" to 1143,
            )
        }

        Then("mutable columns can be updated") {
            runtime().use { c ->
                ADMIN_RUNTIME_COLUMN_UPDATES.mapValues { (table, columns) -> c.errorCodeOf("UPDATE $table SET ${columns.first()} = ${columns.first()}") }
            } shouldBe ADMIN_RUNTIME_COLUMN_UPDATES.mapValues { null }
        }

        Then("S0 m-4: locking reads (FOR UPDATE / FOR SHARE) are allowed on the column-updatable tables") {
            runtime().use { c ->
                c.autoCommit = false
                try {
                    ADMIN_RUNTIME_COLUMN_UPDATES.keys.associateWith { t ->
                        listOf(c.errorCodeOf("SELECT * FROM $t LIMIT 1 FOR UPDATE"), c.errorCodeOf("SELECT * FROM $t LIMIT 1 FOR SHARE"))
                    }
                } finally {
                    c.rollback()
                }
            } shouldBe ADMIN_RUNTIME_COLUMN_UPDATES.keys.associateWith { listOf(null, null) }
        }

        Then("S0 m-4 contrast: SELECT, INSERT-only (append-only) tables refuse locking reads with 1142") {
            runtime().use { c ->
                c.autoCommit = false
                try {
                    APPEND_ONLY_TABLES.associateWith { c.errorCodeOf("SELECT * FROM $it LIMIT 1 FOR UPDATE") }
                } finally {
                    c.rollback()
                }
            } shouldBe APPEND_ONLY_TABLES.associateWith { 1142 }
        }

        Then("the Spring Session tables allow full DML (session lifecycle and expiry cleanup)") {
            runtime().use { c ->
                val id = "sess-dml-0000000000000000000000000"
                c.update(
                    "INSERT INTO admin_session (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME) " +
                        "VALUES (?, ?, 1, 1, 60, 61)", id, id,
                )
                c.update("INSERT INTO admin_session_attributes (SESSION_PRIMARY_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES) VALUES (?, 'a', x'00')", id)
                listOf(
                    c.update("UPDATE admin_session_attributes SET ATTRIBUTE_BYTES = x'01' WHERE SESSION_PRIMARY_ID = ?", id),
                    c.update("UPDATE admin_session SET LAST_ACCESS_TIME = 2 WHERE PRIMARY_ID = ?", id),
                    c.update("DELETE FROM admin_session WHERE PRIMARY_ID = ?", id),
                )
            } shouldBe listOf(1, 1, 1)
        }

        Then("it cannot change the schema (CREATE / ALTER / DROP / TRUNCATE denied with 1142)") {
            runtime().use { c ->
                mapOf(
                    "create" to c.errorCodeOf("CREATE TABLE sneaky (id INT PRIMARY KEY)"),
                    "alter" to c.errorCodeOf("ALTER TABLE admin_audit_event ADD COLUMN sneaky INT"),
                    "drop" to c.errorCodeOf("DROP TABLE admin_audit_event"),
                    "truncate" to c.errorCodeOf("TRUNCATE TABLE admin_session"),
                )
            } shouldBe mapOf("create" to 1142, "alter" to 1142, "drop" to 1142, "truncate" to 1142)
        }

        Then("it cannot read other owned databases") {
            runtime().use { c ->
                listOf(AdminDbAccounts.MASTER_DATABASE, AdminDbAccounts.PAYMENT_DATABASE, AdminDbAccounts.FINANCIAL_DATABASE).forEach { db ->
                    c.errorCodeOf("SELECT COUNT(*) FROM $db.flyway_schema_history")!! shouldBeIn setOf(1142, 1044, 1146)
                    c.errorCodeOf("USE $db") shouldBe 1044
                }
            }
        }
    }

    Given("the ADMIN migration account") {
        Then("it cannot grant privileges directly (no GRANT OPTION), grant the role, create accounts or call other DBs' procedures") {
            migration().use { c ->
                mapOf(
                    "to master runtime" to c.errorCodeOf("GRANT SELECT ON admin_audit_event TO 'paycore_master'@'%'"),
                    "to admin runtime role" to c.errorCodeOf("GRANT DELETE ON admin_audit_event TO 'paycore_admin_runtime'@'%'"),
                    "role to outside" to c.errorCodeOf("GRANT 'paycore_admin_runtime'@'%' TO 'paycore_payment'@'%'"),
                    "create user" to c.errorCodeOf("CREATE USER 'paycore_outside'@'%' IDENTIFIED BY 'x'"),
                    "payment procedure" to c.errorCodeOf("CALL paycore_payment_privilege.set_runtime_table_privileges('x', 'SELECT')"),
                    "use payment" to c.errorCodeOf("USE paycore_payment"),
                )
            } shouldBe mapOf(
                "to master runtime" to 1142, "to admin runtime role" to 1142, "role to outside" to 1227, "create user" to 1227,
                "payment procedure" to 1370, "use payment" to 1044,
            )
        }

        Then("the privilege procedures refuse disallowed sets, the Flyway history table, unknown or upper-case columns") {
            migration().use { c ->
                mapOf(
                    "extra privilege" to c.errorCodeOf("CALL paycore_admin_privilege.set_runtime_table_privileges('admin_audit_event', 'SELECT, INSERT, UPDATE, DELETE, DROP')"),
                    "flyway history" to c.errorCodeOf("CALL paycore_admin_privilege.set_runtime_table_privileges('flyway_schema_history', 'SELECT')"),
                    "unknown column" to c.errorCodeOf("CALL paycore_admin_privilege.grant_runtime_column_update('admin_account', 'no_such_column')"),
                    "upper-case column" to c.errorCodeOf("CALL paycore_admin_privilege.grant_runtime_column_update('admin_account', 'STATUS')"),
                    "flyway history column" to c.errorCodeOf("CALL paycore_admin_privilege.grant_runtime_column_update('flyway_schema_history', 'success')"),
                )
            } shouldBe mapOf(
                "extra privilege" to 1644, "flyway history" to 1644, "unknown column" to 1644, "upper-case column" to 1644,
                "flyway history column" to 1644,
            )
        }
    }
}) {
    companion object {
        /** 표 단위 runtime 권한 기대값(db/runtime-privilege/admin). 권한을 넓히는 변경은 이 기대값을 함께 바꾸는 검토된 변경이어야 한다. */
        val ADMIN_RUNTIME_TABLE_PRIVILEGES: Map<String, String> = linkedMapOf(
            "admin_account" to "SELECT, INSERT",
            "admin_approval_request" to "SELECT, INSERT",
            "admin_audit_event" to "SELECT, INSERT",
            "admin_bootstrap" to "SELECT, INSERT",
            "admin_enrollment_code" to "SELECT, INSERT",
            // ADMIN-PAYMENT-OPS-001 V6(§4.11): 명령·대사 발견 INSERT-only, 실행 행 SELECT, INSERT + 열 UPDATE, checkpoint는 SELECT + 열 UPDATE(행은 V5가 만든다).
            "admin_operator_cancel_command" to "SELECT, INSERT",
            "admin_ops_reconciliation_checkpoint" to "SELECT",
            "admin_ops_reconciliation_finding" to "SELECT, INSERT",
            "admin_owner_execution" to "SELECT, INSERT",
            "admin_recovery_episode" to "SELECT, INSERT",
            "admin_role_event" to "SELECT, INSERT",
            "admin_session" to "SELECT, INSERT, UPDATE, DELETE",
            "admin_session_attributes" to "SELECT, INSERT, UPDATE, DELETE",
        )

        /** 열 단위 UPDATE 기대값(§4.2.2 — 생성 불변 열 제외). JPA Entity의 updatable 열 목록과 같아야 한다(S3에서 대조 시험 추가). */
        val ADMIN_RUNTIME_COLUMN_UPDATES: Map<String, List<String>> = linkedMapOf(
            "admin_account" to listOf(
                "display_name", "status", "status_before_suspend", "pending_purpose", "pending_basis", "password_hash",
                "totp_secret_ciphertext", "totp_key_version", "last_totp_step", "failed_attempts", "locked_at", "active_roles",
                "suspended_roles", "session_generation", "security_version", "version", "updated_at",
                // S5 리뷰(D-ADMIN1-014): 자격 발급 근거 — 코드 발급(재설정·재발급) TX가 바꾼다.
                "credential_basis", "credential_basis_requester", "credential_basis_approver", "credential_basis_carried",
            ),
            "admin_approval_request" to listOf("status", "decided_by", "decided_by_display_name", "decided_at", "result_code", "version"),
            "admin_enrollment_code" to listOf("used_at", "revoked_at"),
            "admin_recovery_episode" to listOf("closed_at"),
            // V6: 실행 행의 변경 열(= JpaOwnerExecutionEntity updatable 열). 저장 본문·지문·승인 시각·기한·실행자는 불변(1143).
            "admin_owner_execution" to listOf(
                "attempt_count", "next_attempt_at", "last_attempt_at", "last_response_at", "last_failure", "integrity_flag", "resend_halted_reason",
                "owner_result_code", "owner_reference", "version",
            ),
            "admin_ops_reconciliation_checkpoint" to listOf("watermark", "last_run_at", "last_full_scan_at", "version"),
        )

        val APPEND_ONLY_TABLES = listOf("admin_audit_event", "admin_bootstrap", "admin_role_event", "admin_operator_cancel_command", "admin_ops_reconciliation_finding")

        /** 초기화 스크립트의 ADMIN 건너뜀 안내(정확한 문구 — .env.example 수렴 절차가 "이 줄이 없어야 한다"로 참조). */
        const val ADMIN_SKIP_MESSAGE = "01-owned-databases.sh: ADMIN passwords not set — skipping paycore_admin (compose.yaml requires them)"
        const val ADMIN_EXISTS_MESSAGE = "01-owned-databases.sh: ADMIN passwords not set but paycore_admin already exists"

        /** 대표 생성 불변 열(UPDATE 1143). */
        val IMMUTABLE_COLUMNS: Map<String, List<String>> = linkedMapOf(
            "admin_account" to listOf("actor_id", "login_name", "created_at"),
            "admin_approval_request" to listOf(
                "approval_id", "kind", "target_kind", "target_actor_id", "target_ref", "target_security_version", "target_snapshot", "requested_by", "requested_at", "expires_at",
            ),
            "admin_owner_execution" to listOf("approval_id", "request_body", "fingerprint", "approved_at", "execute_before", "executor_actor_id"),
            "admin_ops_reconciliation_checkpoint" to listOf("id"),
            "admin_enrollment_code" to listOf("code_hash", "target_actor_id", "purpose", "basis", "expires_at"),
            "admin_recovery_episode" to listOf("episode_no", "opened_at", "expires_at"),
        )

        private val FIRST_COLUMN = mapOf(
            "admin_audit_event" to "event_id", "admin_bootstrap" to "id", "admin_role_event" to "event_id", "admin_operator_cancel_command" to "approval_id",
            "admin_ops_reconciliation_finding" to "finding_id",
        )
        fun firstColumn(table: String): String = FIRST_COLUMN.getValue(table)
    }
}
