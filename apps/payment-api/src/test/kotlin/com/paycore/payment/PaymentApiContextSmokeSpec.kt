package com.paycore.payment

import com.paycore.payment.support.MySqlTestContainerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 기술 조합 smoke test: Boot 4 컨텍스트가 실제 MySQL 8.4에 연결되고 Flyway·JPA·jOOQ가 기동하는지 확인한다.
 * 업무 규칙은 검사하지 않는다. migration이 아직 없으므로 pending=0 검사는 첫 migration 전까지 자명하게 참이다.
 */
@SpringBootTest
@Import(MySqlTestContainerConfig::class)
class PaymentApiContextSmokeSpec(
    private val jdbcTemplate: JdbcTemplate,
    private val flyway: Flyway,
    private val entityManagerFactory: EntityManagerFactory,
    private val dsl: DSLContext,
) : FunSpec({

    test("connects to MySQL 8.4 LTS") {
        jdbcTemplate.queryForObject("SELECT VERSION()", String::class.java)!! shouldStartWith "8.4."
    }

    test("Flyway runs against payment migration location") {
        flyway.configuration.locations.map { it.descriptor } shouldBe listOf("classpath:db/migration/payment")
        flyway.info().pending().size shouldBe 0
    }

    test("JPA EntityManagerFactory opens") {
        entityManagerFactory.isOpen shouldBe true
    }

    test("jOOQ DSLContext uses MySQL dialect and executes query") {
        dsl.dialect() shouldBe SQLDialect.MYSQL
        dsl.selectOne().fetchOne(0, Int::class.java) shouldBe 1
    }
})
