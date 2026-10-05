package com.paycore.financial.techsmoke

import com.paycore.testsupport.MySqlTestContainerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

/**
 * Spring Batch 기술 조합 smoke: JDBC JobRepository(Flyway가 만든 metadata) 위에서
 * chunk 실패 → 같은 JobParameters로 재시작 → 마지막 commit 지점부터 이어서 완료되는지 실제 MySQL로 확인한다.
 * 업무 Job이 아니며, 재시작 성공이 외부 효과 exactly-once를 뜻하지 않는다.
 */
@SpringBootTest
@Import(MySqlTestContainerConfig::class, TechSmokeBatchJobConfig::class)
@TestPropertySource(
    properties = ["spring.flyway.locations=classpath:db/migration/financial,classpath:db/testmigration/financial"],
)
class TechSmokeBatchRestartSpec(
    private val jobOperator: JobOperator,
    private val jdbcTemplate: JdbcTemplate,
    private val switch: TechSmokeFailureSwitch,
    @Qualifier(TechSmokeBatchJobConfig.PROCESS_FAILURE_JOB) private val processFailureJob: Job,
    @Qualifier(TechSmokeBatchJobConfig.WRITE_FAILURE_JOB) private val writeFailureJob: Job,
) : FunSpec({

    fun writtenIds(): List<Int> =
        jdbcTemplate.queryForList("SELECT item_id FROM tech_smoke_batch_out ORDER BY item_id", Int::class.java)
            .map { requireNotNull(it) }

    fun countRows(sql: String, jobName: String): Int =
        jdbcTemplate.queryForObject(sql, Int::class.java, jobName)!!

    fun jobInstanceCount(jobName: String) =
        countRows("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE WHERE JOB_NAME = ?", jobName)

    fun jobExecutionCount(jobName: String) = countRows(
        """
        SELECT COUNT(*) FROM BATCH_JOB_EXECUTION e
        JOIN BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID
        WHERE i.JOB_NAME = ?
        """.trimIndent(),
        jobName,
    )

    fun JobExecution.singleStep() = stepExecutions.single()

    fun failThenRestart(job: Job, mode: FailureMode, params: JobParameters) {
        switch.mode = mode
        val first = jobOperator.start(job, params)

        first.status shouldBe BatchStatus.FAILED
        // chunk 1(1..3)만 commit, 실패한 chunk 2(4..6)는 rollback. reader 위치도 3에 머문다.
        writtenIds() shouldBe listOf(1, 2, 3)
        first.singleStep().commitCount shouldBe 1L

        switch.mode = FailureMode.NONE
        val second = jobOperator.start(job, params)

        second.status shouldBe BatchStatus.COMPLETED
        second.jobInstance.instanceId shouldBe first.jobInstance.instanceId
        // 재시작은 저장된 위치(3) 다음부터 읽는다: 4..10 = 7건. PK 충돌로 우연히 통과한 것이 아님을 보인다.
        second.singleStep().readCount shouldBe 7L
        second.singleStep().writeCount shouldBe 7L
        writtenIds() shouldBe (1..10).toList()
        jobInstanceCount(job.name) shouldBe 1
        jobExecutionCount(job.name) shouldBe 2
    }

    beforeTest {
        switch.mode = FailureMode.NONE
        jdbcTemplate.update("DELETE FROM tech_smoke_batch_out")
    }

    test("processor failure on item 6 fails the job with 3 rows; restart with same parameters completes 10 rows once") {
        val params = JobParametersBuilder().addString("techSmoke.run", "process-failure").toJobParameters()
        failThenRestart(processFailureJob, FailureMode.PROCESS_ITEM, params)
    }

    test("failure after JDBC write of chunk 4..6 rolls those rows back; restart completes 10 rows once") {
        val params = JobParametersBuilder().addString("techSmoke.run", "write-failure").toJobParameters()
        failThenRestart(writeFailureJob, FailureMode.AFTER_WRITE, params)
    }

    test("batch metadata tables are created by the provisional test-only Flyway V0.1 migration") {
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '0.1' AND success = 1",
            Int::class.java,
        ) shouldBe 1
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'BATCH_JOB_INSTANCE'",
            Int::class.java,
        ) shouldBe 1
    }
})
