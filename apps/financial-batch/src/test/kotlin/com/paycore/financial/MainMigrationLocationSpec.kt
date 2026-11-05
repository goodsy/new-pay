package com.paycore.financial

import com.paycore.testsupport.MySqlTestContainerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 운영 설정(main migration 위치만)으로 기동했을 때의 schema를 고정한다. Q-BATCH-META 답변(D-ACQ-007 — Batch metadata는 FINANCIAL DB 같은 schema)에 따라
 * BATCH_*는 main V1이 만들고, 결제 사실 Inbox·투영은 main V2가 만든다(FIN-FACT-RELAY-001). 이전 기대값(main 비어 있음·BATCH_* 없음)은 "답변 후 그 결정에 맞게 바꾼다"고
 * 적어 둔 그 결정으로 바뀌었다(완화 아님 — 표가 생겼다).
 */
@SpringBootTest
@Import(MySqlTestContainerConfig::class)
class MainMigrationLocationSpec(
    private val flyway: Flyway,
    private val jdbcTemplate: JdbcTemplate,
) : FunSpec({

    fun tables(pattern: String): Set<String> = jdbcTemplate.queryForList(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE ?", String::class.java, pattern,
    ).filterNotNull().toSet()

    test("context starts with only the main FINANCIAL migration location, applying V1 (batch metadata) and V2 (payment fact inbox)") {
        flyway.configuration.locations.map { it.descriptor } shouldBe listOf("classpath:db/migration/financial")
        flyway.info().applied().map { it.version.version } shouldBe listOf("1", "2")
        flyway.info().applied().all { it.state.isApplied && !it.state.isFailed } shouldBe true
    }

    test("D-ACQ-007: Spring Batch metadata tables exist in the FINANCIAL schema itself (main V1)") {
        tables("BATCH\\_%") shouldBe setOf(
            "BATCH_JOB_INSTANCE", "BATCH_JOB_EXECUTION", "BATCH_JOB_EXECUTION_PARAMS", "BATCH_STEP_EXECUTION", "BATCH_STEP_EXECUTION_CONTEXT",
            "BATCH_JOB_EXECUTION_CONTEXT", "BATCH_STEP_EXECUTION_SEQ", "BATCH_JOB_EXECUTION_SEQ", "BATCH_JOB_INSTANCE_SEQ",
        )
    }

    test("FIN-FACT-RELAY-001: payment fact inbox and projection tables exist (main V2) and no test-only table leaked into the main schema") {
        tables("payment\\_fact\\_%") shouldBe setOf(
            "payment_fact_inbox", "payment_fact_stream", "payment_fact_authorization", "payment_fact_cancellation", "payment_fact_hold",
        )
        tables("tech\\_smoke\\_%") shouldBe emptySet()
    }
})
