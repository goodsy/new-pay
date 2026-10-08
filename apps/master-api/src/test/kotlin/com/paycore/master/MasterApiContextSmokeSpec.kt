package com.paycore.master

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 기술 조합 smoke(MASTER-BOOT-001): Boot 4 컨텍스트가 실제 MySQL 8.4에 **MASTER DB(`paycore_master`)로만** 연결되고
 * Flyway·JPA·jOOQ가 기동하는지 확인한다. 업무 규칙은 없다(업무 테이블은 MASTER-DOMAIN-001).
 * 운영 migration 위치는 아직 비어 있어 적용된 것은 시험 전용 V900(기술 probe)뿐이다.
 */
@MasterDbTest
class MasterApiContextSmokeSpec(
    private val jdbcTemplate: JdbcTemplate,
    private val flyway: Flyway,
    private val entityManagerFactory: EntityManagerFactory,
    private val dsl: DSLContext,
) : FunSpec({

    fun string(sql: String): String = jdbcTemplate.queryForObject(sql, String::class.java)!!

    test("connects to MySQL 8.4 LTS") {
        string("SELECT VERSION()") shouldStartWith "8.4."
    }

    test("the application pool is connected to the MASTER database only, as the DML-only runtime account") {
        string("SELECT DATABASE()") shouldBe MasterDbAccounts.MASTER_DATABASE
        string("SELECT CURRENT_USER()") shouldBe "${MasterDbAccounts.MASTER_RUNTIME_USER}@%"
    }

    test("Flyway applies the MASTER migration locations with the migration account") {
        flyway.configuration.locations.map { it.descriptor } shouldBe
            listOf(MasterDbAccounts.MAIN_MIGRATIONS, MasterDbAccounts.TEST_MIGRATIONS)
        flyway.info().pending().size shouldBe 0
        // 운영 migration은 아직 없다. 시험 전용 V900만 적용된다.
        flyway.info().applied().map { it.version.version } shouldBe listOf("900")
        jdbcTemplate.queryForList("SELECT DISTINCT installed_by FROM flyway_schema_history", String::class.java) shouldBe
            listOf(MasterDbAccounts.MASTER_MIGRATION_USER)
    }

    test("no business tables exist in the MASTER schema yet (MASTER-DOMAIN-001 adds them)") {
        jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name",
            String::class.java,
        ) shouldBe listOf("flyway_schema_history", "tech_session_probe")
    }

    test("JPA EntityManagerFactory opens (ddl-auto=validate, no entities yet)") {
        entityManagerFactory.isOpen shouldBe true
        entityManagerFactory.metamodel.entities.size shouldBe 0
    }

    test("jOOQ DSLContext uses MySQL dialect and executes query") {
        dsl.dialect() shouldBe SQLDialect.MYSQL
        dsl.selectOne().fetchOne(0, Int::class.java) shouldBe 1
    }
})
