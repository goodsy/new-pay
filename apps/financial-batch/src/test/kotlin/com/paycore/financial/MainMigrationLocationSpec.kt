package com.paycore.financial

import com.paycore.testsupport.MySqlTestContainerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 운영 설정(main migration 위치만)으로 기동했을 때의 현재 상태를 고정한다.
 * Batch metadata 위치는 Q-BATCH-META 답변 전이라 main migration이 비어 있고, 따라서 BATCH_* 테이블이 없다.
 * 답변 후 정식 migration을 추가하면 이 시험의 기대값을 그 결정에 맞게 바꾼다.
 */
@SpringBootTest
@Import(MySqlTestContainerConfig::class)
class MainMigrationLocationSpec(
    private val flyway: Flyway,
    private val jdbcTemplate: JdbcTemplate,
) : FunSpec({

    test("context starts with only the main FINANCIAL migration location") {
        flyway.configuration.locations.map { it.descriptor } shouldBe listOf("classpath:db/migration/financial")
        flyway.info().applied().size shouldBe 0
    }

    test("no Spring Batch metadata tables exist before Q-BATCH-META is answered") {
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE 'BATCH\\_%'",
            Int::class.java,
        ) shouldBe 0
    }
})
