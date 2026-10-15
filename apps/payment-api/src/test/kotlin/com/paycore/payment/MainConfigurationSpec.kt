package com.paycore.payment

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

/**
 * 운영 설정 파일(application.yml) 자체의 DB 계정·Flyway 값을 원문으로 고정한다(PAYMENT-DBACCOUNT-001 D2, master-api MainConfigurationSpec과
 * 같은 관례). DB 시험 컨텍스트는 시험 전용 migration 위치를 더하므로 운영 위치·계정 바인딩은 여기서 본다.
 */
class MainConfigurationSpec : FunSpec({

    val yaml = YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).single()
    fun value(name: String): String? = yaml.getProperty(name)?.toString()

    test("Flyway applies the PAYMENT schema migrations and the PAYMENT runtime privilege migrations") {
        value("spring.flyway.locations") shouldBe "classpath:db/migration/payment,classpath:db/runtime-privilege/payment"
    }

    test("datasource binds the PAYMENT runtime-account variables; Flyway binds the PAYMENT migration-account variables (same names as master-api)") {
        mapOf(
            "spring.datasource.url" to value("spring.datasource.url"),
            "spring.datasource.username" to value("spring.datasource.username"),
            "spring.datasource.password" to value("spring.datasource.password"),
            "spring.flyway.user" to value("spring.flyway.user"),
            "spring.flyway.password" to value("spring.flyway.password"),
        ) shouldBe mapOf(
            "spring.datasource.url" to "\${PAYCORE_PAYMENT_DB_URL}",
            "spring.datasource.username" to "\${PAYCORE_PAYMENT_DB_USERNAME}",
            "spring.datasource.password" to "\${PAYCORE_PAYMENT_DB_PASSWORD}",
            "spring.flyway.user" to "\${PAYCORE_PAYMENT_DB_MIGRATION_USERNAME}",
            "spring.flyway.password" to "\${PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD}",
        )
    }

    test("the Flyway migration session (separate connection, no Hikari driver properties) is set to UTC") {
        value("spring.flyway.init-sqls") shouldBe "SET time_zone = '+00:00'"
    }

    test("Hibernate JDBC error text (it carries duplicate key values: idempotency key, order ID, paymentKey) is not logged (n-4)") {
        value("logging.level.org.hibernate.orm.jdbc.error") shouldBe "OFF"
        value("logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper") shouldBe "OFF"
    }

    test("the session time zone comes only from the driver properties (L-10): UTC and forced to the session, the URL is a bare variable") {
        value("spring.datasource.hikari.data-source-properties.connectionTimeZone") shouldBe "UTC"
        value("spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession") shouldBe "true"
    }

    test("the public API has no operational defaults for the internal port and Retry-After (startup fails without them); health has no details") {
        listOf(
            "paycore.payment.api.internal-port",
            "paycore.payment.api.retry-after.eligibility-unavailable",
            "paycore.payment.api.retry-after.temporarily-unavailable",
            "paycore.payment.api.retry-after.request-in-progress",
        ).map(::value) shouldBe listOf(null, null, null, null)
        value("management.endpoint.health.show-details") shouldBe "never"
    }
})
