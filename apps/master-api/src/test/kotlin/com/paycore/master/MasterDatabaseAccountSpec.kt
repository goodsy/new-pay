package com.paycore.master

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.mysql.MySQLContainer
import java.sql.SQLException
import javax.sql.DataSource

/**
 * migration 계정 / runtime 계정 분리(CP-DB-001 결정)를 로컬 개발 초기화 스크립트(docker/mysql/init/01-owned-databases.sh)와
 * master-api 설정에 적용한 결과를 실제 MySQL 8.4.11로 확인한다(MASTER-BOOT-001).
 *
 * - 앱 pool(runtime 계정 `paycore_master`)은 DB 단위 권한이 없고 기본 role `paycore_master_runtime`의 표 단위 권한만 가진다
 *   (PAYMENT-DBACCOUNT-001). 시험 probe 표(V900)는 시험 전용 권한 migration(V901)이 procedure로 전체 DML을 준다.
 *   CREATE/ALTER/DROP/TRUNCATE는 1142(테이블 명령 권한 없음).
 * - schema 변경은 Flyway가 migration 계정(`paycore_master_migration`)으로 한다(설치자 기록은 smoke spec).
 * - 두 계정 모두 다른 소유 DB(PAYMENT/FINANCIAL)에 접근하지 못한다(1044 DB 접근 거부 또는 1142).
 * - migration 계정은 GRANT OPTION이 없어 어떤 계정에도 권한을 직접 주지 못한다(1142). runtime 표 단위 권한은 초기화 스크립트의
 *   procedure `<db>_privilege.set_runtime_table_privileges`(받는 쪽 = 이 DB runtime role 고정, DEFINER = 잠긴 grantor)로만 준다.
 * - 스크립트가 만든 권한 전체(SHOW GRANTS): MASTER·PAYMENT는 migration 계정 / runtime 계정(role만) / runtime role / 잠긴 grantor,
 *   FINANCIAL은 FIN-DBACCOUNT-001(D-FFR-002)부터 같은 구조다. 이 컨테이너엔 FINANCIAL migration 비밀번호가 없어 새 인스턴스 건너뛰기 경로로 runtime 권한 0.
 *
 * 기대값 변경 기록(PAYMENT-DBACCOUNT-001, 정책을 따르는 의도된 변경): MASTER-BOOT-001의 runtime `SELECT, INSERT, UPDATE, DELETE ON <db>.*`
 * 기대값을 'role만'으로 바꿨다. DB 단위 DML은 append-only 확정 사실 표의 UPDATE/DELETE를 DB 권한으로 막지 못하기 때문이다(D1).
 * MASTER 업무 표의 권한은 MASTER-DOMAIN-001이 V2__master_runtime_privileges.sql로 추가했다(현재 main 쓰기 경로만 — append-only·insert-only 표는
 * SELECT, INSERT, 잠가 읽거나 갱신하는 표만 UPDATE, DELETE 없음). 기대값 [MASTER_RUNTIME_TABLE_PRIVILEGES].
 */
@MasterDbTest
class MasterDatabaseAccountSpec(
    private val jdbcTemplate: JdbcTemplate,
    private val dataSource: DataSource,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun grantsOf(user: String, host: String = "%"): Set<String> = MasterDbAccounts.root(container).use { c ->
        c.column("SHOW GRANTS FOR '$user'@'$host'").filterNot { it.startsWith("GRANT USAGE ON *.*") }.toSet()
    }

    Given("the application pool connects with the MASTER runtime account") {
        Then("it runs DML (INSERT / UPDATE / SELECT / DELETE) on MASTER tables") {
            jdbcTemplate.update(
                "INSERT INTO tech_session_probe (probe_id, session_zone, observed_at) VALUES ('dml', 'x', UTC_TIMESTAMP(3))",
            ) shouldBe 1
            jdbcTemplate.update("UPDATE tech_session_probe SET session_zone = 'y' WHERE probe_id = 'dml'") shouldBe 1
            jdbcTemplate.queryForObject("SELECT session_zone FROM tech_session_probe WHERE probe_id = 'dml'", String::class.java) shouldBe "y"
            jdbcTemplate.update("DELETE FROM tech_session_probe WHERE probe_id = 'dml'") shouldBe 1
        }

        Then("it cannot change the schema (CREATE / ALTER / DROP / TRUNCATE denied with 1142)") {
            dataSource.connection.use { c ->
                mapOf(
                    "create" to c.errorCodeOf("CREATE TABLE sneaky (id INT PRIMARY KEY)"),
                    "alter" to c.errorCodeOf("ALTER TABLE tech_session_probe ADD COLUMN sneaky INT"),
                    "drop" to c.errorCodeOf("DROP TABLE tech_session_probe"),
                    "truncate" to c.errorCodeOf("TRUNCATE TABLE tech_session_probe"),
                ) shouldBe mapOf("create" to 1142, "alter" to 1142, "drop" to 1142, "truncate" to 1142)
            }
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'sneaky'",
                Int::class.java,
            ) shouldBe 0
        }

        Then("it cannot read other owned databases (no cross-DB access)") {
            dataSource.connection.use { c ->
                for (database in listOf(MasterDbAccounts.PAYMENT_DATABASE, MasterDbAccounts.FINANCIAL_DATABASE)) {
                    val denied = shouldThrow<SQLException> {
                        c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM $database.flyway_schema_history") }
                    }
                    denied.errorCode shouldBeIn setOf(1142, 1044)
                }
            }
        }
    }

    Given("the MASTER migration account") {
        Then("it cannot use other owned databases either") {
            MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_MIGRATION_USER).use { c ->
                c.errorCodeOf("USE ${MasterDbAccounts.PAYMENT_DATABASE}") shouldBe 1044
                c.errorCodeOf("USE ${MasterDbAccounts.FINANCIAL_DATABASE}") shouldBe 1044
            }
        }

        Then("it cannot grant privileges to any account directly (no GRANT OPTION), grant the runtime role, or create accounts") {
            MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_MIGRATION_USER).use { c ->
                mapOf(
                    "to payment runtime" to c.errorCodeOf("GRANT SELECT ON tech_session_probe TO 'paycore_payment'@'%'"),
                    "to master runtime role" to c.errorCodeOf("GRANT DELETE ON tech_session_probe TO 'paycore_master_runtime'@'%'"),
                    "role to outside" to c.errorCodeOf("GRANT 'paycore_master_runtime'@'%' TO 'paycore_payment'@'%'"),
                    "create user" to c.errorCodeOf("CREATE USER 'paycore_outside'@'%' IDENTIFIED BY 'x'"),
                    "payment procedure" to c.errorCodeOf("CALL paycore_payment_privilege.set_runtime_table_privileges('x', 'SELECT')"),
                )
            } shouldBe mapOf(
                "to payment runtime" to 1142, "to master runtime role" to 1142, "role to outside" to 1227, "create user" to 1227,
                "payment procedure" to 1370,
            )
        }

        Then("the privilege procedure refuses privilege sets outside the allowed list and the Flyway history table") {
            MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_MIGRATION_USER).use { c ->
                mapOf(
                    "extra privilege" to
                        c.errorCodeOf("CALL paycore_master_privilege.set_runtime_table_privileges('tech_session_probe', 'SELECT, INSERT, UPDATE, DELETE, DROP')"),
                    "flyway history" to c.errorCodeOf("CALL paycore_master_privilege.set_runtime_table_privileges('flyway_schema_history', 'SELECT')"),
                )
            } shouldBe mapOf("extra privilege" to 1644, "flyway history" to 1644)
        }
    }

    Given("the local init script (docker/mysql/init/01-owned-databases.sh)") {
        Then("MASTER and PAYMENT: migration account (DB ALL + procedure EXECUTE), role-only runtime account, runtime role, locked grantor; FINANCIAL runtime account has no DB-level privilege (FIN-DBACCOUNT-001, D-FFR-002)") {
            mapOf(
                MasterDbAccounts.MASTER_RUNTIME_USER to grantsOf(MasterDbAccounts.MASTER_RUNTIME_USER),
                MasterDbAccounts.MASTER_RUNTIME_ROLE to grantsOf(MasterDbAccounts.MASTER_RUNTIME_ROLE),
                MasterDbAccounts.MASTER_MIGRATION_USER to grantsOf(MasterDbAccounts.MASTER_MIGRATION_USER),
                MasterDbAccounts.MASTER_GRANTOR to grantsOf(MasterDbAccounts.MASTER_GRANTOR, "localhost"),
                MasterDbAccounts.PAYMENT_RUNTIME_USER to grantsOf(MasterDbAccounts.PAYMENT_RUNTIME_USER),
                MasterDbAccounts.PAYMENT_RUNTIME_ROLE to grantsOf(MasterDbAccounts.PAYMENT_RUNTIME_ROLE),
                MasterDbAccounts.PAYMENT_MIGRATION_USER to grantsOf(MasterDbAccounts.PAYMENT_MIGRATION_USER),
                MasterDbAccounts.PAYMENT_GRANTOR to grantsOf(MasterDbAccounts.PAYMENT_GRANTOR, "localhost"),
                MasterDbAccounts.FINANCIAL_USER to grantsOf(MasterDbAccounts.FINANCIAL_USER),
            ) shouldBe mapOf(
                "paycore_master" to setOf("GRANT `paycore_master_runtime`@`%` TO `paycore_master`@`%`"),
                // 운영 업무 표(V2 — 현재 main 쓰기 경로만, append-only·insert-only 표는 SELECT, INSERT, DELETE 없음)와 시험 전용 probe 표(V901).
                "paycore_master_runtime" to MASTER_RUNTIME_TABLE_PRIVILEGES.map { (table, privileges) ->
                    "GRANT $privileges ON `paycore_master`.`$table` TO `paycore_master_runtime`@`%`"
                }.toSet() + "GRANT SELECT, INSERT, UPDATE, DELETE ON `paycore_master`.`tech_session_probe` TO `paycore_master_runtime`@`%`",
                "paycore_master_migration" to setOf(
                    "GRANT ALL PRIVILEGES ON `paycore_master`.* TO `paycore_master_migration`@`%`",
                    "GRANT EXECUTE ON PROCEDURE `paycore_master_privilege`.`set_runtime_table_privileges` TO `paycore_master_migration`@`%`",
                ),
                "paycore_master_grantor" to setOf(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON `paycore_master`.* TO `paycore_master_grantor`@`localhost` WITH GRANT OPTION",
                    "GRANT EXECUTE ON PROCEDURE `paycore_master_privilege`.`set_runtime_table_privileges` TO `paycore_master_grantor`@`localhost`",
                ),
                "paycore_payment" to setOf("GRANT `paycore_payment_runtime`@`%` TO `paycore_payment`@`%`"),
                // 이 컨테이너에는 PAYMENT migration이 적용되지 않았다(PAYMENT 표 단위 권한은 payment-api DatabaseAccountSeparationSpec).
                "paycore_payment_runtime" to emptySet(),
                "paycore_payment_migration" to setOf(
                    "GRANT ALL PRIVILEGES ON `paycore_payment`.* TO `paycore_payment_migration`@`%`",
                    "GRANT EXECUTE ON PROCEDURE `paycore_payment_privilege`.`set_runtime_table_privileges` TO `paycore_payment_migration`@`%`",
                    // WEBHOOK-001 D-WH-009: PAYMENT 전용 열 단위 UPDATE helper(추가만 — MASTER·FINANCIAL 불변).
                    "GRANT EXECUTE ON PROCEDURE `paycore_payment_privilege`.`grant_runtime_column_update` TO `paycore_payment_migration`@`%`",
                ),
                "paycore_payment_grantor" to setOf(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON `paycore_payment`.* TO `paycore_payment_grantor`@`localhost` WITH GRANT OPTION",
                    "GRANT EXECUTE ON PROCEDURE `paycore_payment_privilege`.`set_runtime_table_privileges` TO `paycore_payment_grantor`@`localhost`",
                    // WEBHOOK-001 D-WH-009: PAYMENT 전용 열 단위 UPDATE helper(추가만 — MASTER·FINANCIAL 불변).
                    "GRANT EXECUTE ON PROCEDURE `paycore_payment_privilege`.`grant_runtime_column_update` TO `paycore_payment_grantor`@`localhost`",
                ),
                // FIN-DBACCOUNT-001(D-FFR-002): 이전 기대값(DB ALL)이 바로 없애기로 한 위험 상태다 — 강화. 이 컨테이너엔 FINANCIAL migration 비밀번호가 없어
                // 새 인스턴스 건너뛰기 경로(명세 I-2)로 role 설치도 생략되므로 runtime 계정 권한은 0이다.
                "paycore_financial" to emptySet(),
            )
        }

        Then("every MASTER business table has a runtime privilege migration (a new table without one fails here)") {
            MasterDbAccounts.root(container).use { c ->
                val tables = c.column(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'paycore_master' AND table_type = 'BASE TABLE' " +
                        "AND table_name NOT IN ('flyway_schema_history') ORDER BY table_name",
                ).toSet()
                val granted = c.column(
                    "SELECT Table_name FROM mysql.tables_priv WHERE Db = 'paycore_master' AND User = 'paycore_master_runtime'",
                ).toSet()
                tables - granted
            } shouldBe emptySet()
        }

        Then("no triggers, routines, events or views exist in the MASTER database (any future use needs a reviewed expectation change)") {
            MasterDbAccounts.root(container).use { c ->
                listOf("TRIGGERS" to "TRIGGER_SCHEMA", "ROUTINES" to "ROUTINE_SCHEMA", "EVENTS" to "EVENT_SCHEMA", "VIEWS" to "TABLE_SCHEMA")
                    .associate { (view, column) ->
                        view to c.column("SELECT COUNT(*) FROM information_schema.$view WHERE $column = 'paycore_master'").single()
                    }
            } shouldBe mapOf("TRIGGERS" to "0", "ROUTINES" to "0", "EVENTS" to "0", "VIEWS" to "0")
        }

        Then("runtime accounts activate their runtime role by default; roles and grantors cannot log in (account locked)") {
            MasterDbAccounts.root(container).use { c ->
                mapOf(
                    "default roles" to c.column(
                        "SELECT CONCAT(USER, '->', DEFAULT_ROLE_USER) FROM mysql.default_roles WHERE USER LIKE 'paycore%' ORDER BY USER",
                    ),
                    "locked" to c.column(
                        "SELECT CONCAT(User, '@', Host) FROM mysql.user WHERE User LIKE 'paycore%' AND account_locked = 'Y' ORDER BY User",
                    ),
                )
            } shouldBe mapOf(
                "default roles" to listOf("paycore_master->paycore_master_runtime", "paycore_payment->paycore_payment_runtime"),
                "locked" to listOf(
                    "paycore_master_grantor@localhost", "paycore_master_runtime@%", "paycore_payment_grantor@localhost",
                    "paycore_payment_runtime@%",
                ),
            )
        }
    }
}) {
    companion object {
        /**
         * MASTER runtime role의 표 단위 권한 기대값(V2__master_runtime_privileges.sql + V4__entitlement_runtime_privileges.sql, main 코드 기준). 잠가 읽거나 갱신하는 표만
         * UPDATE, 나머지는 SELECT, INSERT. 권한을 넓히는 변경은 이 기대값을 함께 바꾸는 검토된 변경이어야 한다.
         */
        val MASTER_RUNTIME_TABLE_PRIVILEGES: Map<String, String> = linkedMapOf(
            // 기대값 변경(MERCHANT-OPS-002 B2-2 V10, 명세 §9.3·§8.2 B2-2-4): 승인 근거 append-only — SELECT, INSERT만(UPDATE·DELETE 1142).
            "admin_approval_evidence" to "SELECT, INSERT",
            "admin_request" to "SELECT, INSERT, UPDATE",
            "business" to "SELECT, INSERT, UPDATE",
            // 기대값 변경(BUSINESS-PROFILE-001 V16 — 목록 증가, 명세 §5.4): 사업자 하위 표 5개 — 종료 기록(effective_to·ended_by)·등록정보 제자리 수정용 UPDATE, DELETE 없음.
            "business_address" to "SELECT, INSERT, UPDATE",
            "business_category" to "SELECT, INSERT, UPDATE",
            "business_contact" to "SELECT, INSERT, UPDATE",
            "business_registration" to "SELECT, INSERT, UPDATE",
            "business_representative" to "SELECT, INSERT, UPDATE",
            // MASTER-SNAPSHOT-002 V6: 카드사 개통 상태 전이(UPDATE, @Version)·이력(append-only).
            "card_route_activation" to "SELECT, INSERT, UPDATE",
            "card_route_activation_event" to "SELECT, INSERT",
            "contract" to "SELECT, INSERT, UPDATE",
            // 기대값 변경(MERCHANT-FLOW-001 FLOW-1 V20 — 목록 증가): 접수 건 현재 상태(전이 UPDATE, @Version)·전이 이력 INSERT 전용(SELECT, INSERT만 — UPDATE·DELETE 1142, §4A.14 M-2).
            "contract_intake" to "SELECT, INSERT, UPDATE",
            "contract_intake_transition" to "SELECT, INSERT",
            "contract_revision" to "SELECT, INSERT",
            "contract_revision_approval" to "SELECT, INSERT",
            "contract_revision_scope_site" to "SELECT, INSERT",
            "contract_service_agreement" to "SELECT, INSERT",
            // MASTER-SNAPSHOT-001 V4: 통화 기록 종료(UPDATE), 기준정보 읽기만, 발행·ACK·이력·확인·철회·심사는 append-only.
            "currency_enablement" to "SELECT, INSERT, UPDATE",
            "currency_unit" to "SELECT",
            "default_designation" to "SELECT, INSERT",
            "entitlement_snapshot_ack" to "SELECT, INSERT",
            "entitlement_snapshot_issue" to "SELECT, INSERT",
            "master_change_history" to "SELECT, INSERT",
            "merchant" to "SELECT, INSERT, UPDATE",
            "merchant_account" to "SELECT, INSERT, UPDATE",
            "merchant_account_site_assignment" to "SELECT, INSERT",
            // 기대값 변경(MERCHANT-CODE-001 MC-1 V14 — 목록 증가): 코드 배정 이력 append-only(SELECT, INSERT만).
            "merchant_code_assignment" to "SELECT, INSERT",
            "merchant_site" to "SELECT, INSERT",
            "merchant_site_review_decision" to "SELECT, INSERT",
            // V4: Relay claim·PUBLISHED·QUARANTINED(AC m-7) — 이전 기대값 SELECT, INSERT에서 검토된 확대.
            "outbox_event" to "SELECT, INSERT, UPDATE",
            // V4: 개통 상태 전이(D-MS-003) — 이전 기대값 SELECT, INSERT에서 검토된 확대.
            "payment_method_activation" to "SELECT, INSERT, UPDATE",
            "payment_method_activation_event" to "SELECT, INSERT",
            // MASTER-SNAPSHOT-002 V6: 정책 Assignment 종료(UPDATE), 정책 버전 등록부 append-only.
            "policy_assignment" to "SELECT, INSERT, UPDATE",
            "policy_version" to "SELECT, INSERT",
            "site_activation_confirmation" to "SELECT, INSERT",
            "site_activation_confirmation_revocation" to "SELECT, INSERT",
            // MASTER-SNAPSHOT-002 V6: 한도 정책 종료(UPDATE, 값 컬럼은 updatable=false).
            "transaction_limit_policy" to "SELECT, INSERT, UPDATE",
        )
    }
}
