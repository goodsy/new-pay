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
 * - 앱 pool(runtime 계정 `paycore_master`)은 MASTER DB에서 DML만 된다. CREATE/ALTER/DROP/TRUNCATE는 1142(테이블 명령 권한 없음).
 * - schema 변경은 Flyway가 migration 계정(`paycore_master_migration`)으로 한다(설치자 기록은 smoke spec).
 * - 두 계정 모두 다른 소유 DB(PAYMENT/FINANCIAL)에 접근하지 못한다(1044 DB 접근 거부 또는 1142).
 * - 스크립트가 만든 권한 전체(SHOW GRANTS): MASTER·PAYMENT는 계정 분리, FINANCIAL은 Batch metadata 위치·권한 미결(Q-BATCH-META)이라
 *   기존 단일 계정을 유지한다.
 *
 * append-only 확정 사실 테이블의 표 단위 권한(UPDATE/DELETE 제외)은 MASTER에 아직 테이블이 없어 원칙만 기록했다
 * (docker/mysql/init 주석, MASTER-DOMAIN-001에서 해당 테이블과 함께 GRANT·시험 추가).
 */
@MasterDbTest
class MasterDatabaseAccountSpec(
    private val jdbcTemplate: JdbcTemplate,
    private val dataSource: DataSource,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun grantsOf(user: String): Set<String> = MasterDbAccounts.root(container).use { c ->
        c.column("SHOW GRANTS FOR '$user'@'%'").filterNot { it.startsWith("GRANT USAGE ON *.*") }.toSet()
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
    }

    Given("the local init script (docker/mysql/init/01-owned-databases.sh)") {
        Then("MASTER and PAYMENT have a migration account (ALL on its own DB) and a DML-only runtime account; FINANCIAL is unchanged") {
            mapOf(
                MasterDbAccounts.MASTER_RUNTIME_USER to grantsOf(MasterDbAccounts.MASTER_RUNTIME_USER),
                MasterDbAccounts.MASTER_MIGRATION_USER to grantsOf(MasterDbAccounts.MASTER_MIGRATION_USER),
                MasterDbAccounts.PAYMENT_RUNTIME_USER to grantsOf(MasterDbAccounts.PAYMENT_RUNTIME_USER),
                MasterDbAccounts.PAYMENT_MIGRATION_USER to grantsOf(MasterDbAccounts.PAYMENT_MIGRATION_USER),
                MasterDbAccounts.FINANCIAL_USER to grantsOf(MasterDbAccounts.FINANCIAL_USER),
            ) shouldBe mapOf(
                "paycore_master" to setOf("GRANT SELECT, INSERT, UPDATE, DELETE ON `paycore_master`.* TO `paycore_master`@`%`"),
                "paycore_master_migration" to setOf("GRANT ALL PRIVILEGES ON `paycore_master`.* TO `paycore_master_migration`@`%`"),
                "paycore_payment" to setOf("GRANT SELECT, INSERT, UPDATE, DELETE ON `paycore_payment`.* TO `paycore_payment`@`%`"),
                "paycore_payment_migration" to setOf("GRANT ALL PRIVILEGES ON `paycore_payment`.* TO `paycore_payment_migration`@`%`"),
                "paycore_financial" to setOf("GRANT ALL PRIVILEGES ON `paycore_financial`.* TO `paycore_financial`@`%`"),
            )
        }
    }
})
