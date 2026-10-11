package com.paycore.master

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

/**
 * 운영 설정 파일(application.yml) 자체의 값을 고정한다. DB 시험 컨텍스트는 시험 전용 migration 위치를 추가하므로
 * 운영 migration 위치·계정 바인딩은 여기서 원문으로 본다(MASTER-BOOT-001).
 */
class MainConfigurationSpec : FunSpec({

    val yaml = YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).single()
    fun value(name: String): String? = yaml.getProperty(name)?.toString()

    test("Flyway uses only the MASTER business-schema and MASTER runtime-privilege locations (MASTER-DOMAIN-001)") {
        // 권한 migration은 업무 schema와 다른 위치다: jOOQ 생성기는 업무 schema 위치만 적용한다(ARCHITECTURE-CONVENTIONS §9.2).
        value("spring.flyway.locations") shouldBe "classpath:db/migration/master,classpath:db/runtime-privilege/master"
    }

    test("datasource binds only MASTER runtime-account variables; Flyway binds the MASTER migration-account variables") {
        mapOf(
            "spring.datasource.url" to value("spring.datasource.url"),
            "spring.datasource.username" to value("spring.datasource.username"),
            "spring.datasource.password" to value("spring.datasource.password"),
            "spring.flyway.user" to value("spring.flyway.user"),
            "spring.flyway.password" to value("spring.flyway.password"),
        ) shouldBe mapOf(
            "spring.datasource.url" to "\${PAYCORE_MASTER_DB_URL}",
            "spring.datasource.username" to "\${PAYCORE_MASTER_DB_USERNAME}",
            "spring.datasource.password" to "\${PAYCORE_MASTER_DB_PASSWORD}",
            "spring.flyway.user" to "\${PAYCORE_MASTER_DB_MIGRATION_USERNAME}",
            "spring.flyway.password" to "\${PAYCORE_MASTER_DB_MIGRATION_PASSWORD}",
        )
    }

    test("no other owned database is configured (PAYMENT / FINANCIAL)") {
        val text = ClassPathResource("application.yml").inputStream.use { String(it.readAllBytes()) }
        Regex("PAYCORE_(PAYMENT|FINANCIAL)_|paycore_(payment|financial)", RegexOption.IGNORE_CASE).containsMatchIn(text) shouldBe false
    }

    test("Hibernate JDBC error text (which carries duplicate key values such as registration numbers) is not logged (D-MD-008, review M-2)") {
        value("logging.level.org.hibernate.orm.jdbc.error") shouldBe "OFF"
        value("logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper") shouldBe "OFF"
    }

    test("schema is owned by Flyway; JPA does not open sessions per request") {
        value("spring.jpa.hibernate.ddl-auto") shouldBe "validate"
        value("spring.jpa.open-in-view") shouldBe "false"
    }
})
