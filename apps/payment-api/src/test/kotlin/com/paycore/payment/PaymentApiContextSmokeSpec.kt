package com.paycore.payment

import com.paycore.payment.entitlement.EntitlementDbTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 기술 조합 smoke test: Boot 4 컨텍스트가 실제 MySQL 8.4에 연결되고 Flyway·JPA·jOOQ가 기동하는지 확인한다.
 * 업무 규칙은 검사하지 않는다. 운영 위치는 업무 schema(db/migration/payment: V1 CP-DB-001)와 runtime 표 단위 권한
 * (db/runtime-privilege/payment: V2 PAYMENT-DBACCOUNT-001)이며 pending=0과 V1·V2 적용을 함께 본다. 앱은 runtime 계정, Flyway는 migration 계정이다.
 * JPA Entity가 생겼으므로 컨텍스트 기동 자체가 `ddl-auto=validate`(migration schema ↔ Entity 매핑 일치) 통과의 증거다.
 *
 * MASTER-SNAPSHOT-001(CP-DB-001 review2 L-11): EligibilityTestConfig **없이** 기동한다 — CreatePaymentService가 운영 Port 구현
 * (EntitlementEligibilityAdapter)으로 runtime 계정·실제 MySQL에 붙는다. 운영 위치에 V3(권리 투영)·V4(그 권한)가 더해졌다. 같은 구성의
 * EntitlementDbTest 시험들과 컨텍스트를 공유한다.
 */
@EntitlementDbTest
class PaymentApiContextSmokeSpec(
    private val jdbcTemplate: JdbcTemplate,
    private val flyway: Flyway,
    private val entityManagerFactory: EntityManagerFactory,
    private val dsl: DSLContext,
) : FunSpec({

    test("connects to MySQL 8.4 LTS") {
        jdbcTemplate.queryForObject("SELECT VERSION()", String::class.java)!! shouldStartWith "8.4."
    }

    test("the application pool connects with the PAYMENT runtime account and its runtime role is active") {
        jdbcTemplate.queryForObject("SELECT CURRENT_USER()", String::class.java) shouldBe "${PaymentDbAccounts.PAYMENT_RUNTIME_USER}@%"
        jdbcTemplate.queryForObject("SELECT CURRENT_ROLE()", String::class.java) shouldBe "`${PaymentDbAccounts.PAYMENT_RUNTIME_ROLE}`@`%`"
    }

    test("Flyway runs against payment migration location") {
        flyway.configuration.locations.map { it.descriptor } shouldBe
            listOf("classpath:db/migration/payment", "classpath:db/runtime-privilege/payment")
        flyway.info().pending().size shouldBe 0
        flyway.info().applied().map { it.version.version } shouldBe listOf("1", "2", "3", "4", "5", "6", "7", "8", "8.1", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28")
        flyway.info().applied().map { it.installedBy }.toSet() shouldBe setOf(PaymentDbAccounts.PAYMENT_MIGRATION_USER)
    }

    test("JPA EntityManagerFactory opens") {
        entityManagerFactory.isOpen shouldBe true
    }

    test("jOOQ DSLContext uses MySQL dialect and executes query") {
        dsl.dialect() shouldBe SQLDialect.MYSQL
        dsl.selectOne().fetchOne(0, Int::class.java) shouldBe 1
    }
})
