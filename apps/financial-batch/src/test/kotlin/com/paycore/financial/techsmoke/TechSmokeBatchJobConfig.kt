package com.paycore.financial.techsmoke

import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * TECH-001 Batch 재시작 smoke 전용 Job 구성(테스트 소스에만 존재, 업무 의미 없음).
 *
 * 구조: 1..10 정수 reader → 장애 주입 가능한 processor → JDBC writer(tech_smoke_batch_out). chunk 크기 3.
 * 두 Job은 같은 구성이며 Job 이름만 다르다. 장애 위치는 [TechSmokeFailureSwitch.mode]로 테스트가 고른다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TechSmokeBatchJobConfig {

    @Bean
    fun techSmokeFailureSwitch(): TechSmokeFailureSwitch = TechSmokeFailureSwitch()

    @Bean(PROCESS_FAILURE_JOB)
    fun techSmokeProcessFailureJob(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        dataSource: DataSource,
        switch: TechSmokeFailureSwitch,
    ): Job = smokeJob(PROCESS_FAILURE_JOB, jobRepository, transactionManager, dataSource, switch)

    @Bean(WRITE_FAILURE_JOB)
    fun techSmokeWriteFailureJob(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        dataSource: DataSource,
        switch: TechSmokeFailureSwitch,
    ): Job = smokeJob(WRITE_FAILURE_JOB, jobRepository, transactionManager, dataSource, switch)

    private fun smokeJob(
        name: String,
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        dataSource: DataSource,
        switch: TechSmokeFailureSwitch,
    ): Job {
        val step = StepBuilder("$name.step", jobRepository)
            .chunk<Int, Int>(CHUNK_SIZE)
            .transactionManager(transactionManager)
            // ItemStream을 구현한 reader는 builder가 stream으로 자동 등록한다(open/update 호출).
            .reader(RestartableSequenceReader(LAST_ITEM))
            .processor(FailOnItemProcessor(switch))
            .writer(FailAfterWriteWriter(jdbcWriter(dataSource), switch))
            .build()
        return JobBuilder(name, jobRepository).start(step).build()
    }

    private fun jdbcWriter(dataSource: DataSource): ItemWriter<Int> =
        JdbcBatchItemWriterBuilder<Int>()
            .dataSource(dataSource)
            .sql("INSERT INTO tech_smoke_batch_out (item_id) VALUES (?)")
            .itemPreparedStatementSetter { item, ps -> ps.setInt(1, item) }
            .build()
            .also { it.afterPropertiesSet() }

    companion object {
        const val PROCESS_FAILURE_JOB = "techSmokeProcessFailureJob"
        const val WRITE_FAILURE_JOB = "techSmokeWriteFailureJob"
        const val CHUNK_SIZE = 3
        const val LAST_ITEM = 10
        const val FAILING_ITEM = 6
    }
}

enum class FailureMode {
    NONE,

    /** item 6 처리 중 실패한다(그 chunk의 쓰기 전). */
    PROCESS_ITEM,

    /** item 4..6 INSERT가 DB에 실행된 뒤 같은 chunk TX 안에서 실패한다(쓰기 후 rollback 확인). */
    AFTER_WRITE,
}

/** 테스트가 제어하는 장애 주입 스위치. */
class TechSmokeFailureSwitch {
    @Volatile
    var mode: FailureMode = FailureMode.NONE
}

class TechSmokeInjectedFailure(message: String) : RuntimeException(message)

/**
 * 1..lastItem을 순서대로 읽는 reader. 읽은 위치를 ExecutionContext에 저장한다.
 * Batch는 chunk 처리 후 update()로 위치를 받아 chunk와 함께 저장하므로, 재시작 시 open()이 마지막 commit 위치부터 이어 읽는다.
 */
class RestartableSequenceReader(private val lastItem: Int) : ItemStreamReader<Int> {
    private var position = 0

    override fun open(executionContext: ExecutionContext) {
        position = executionContext.getInt(POSITION_KEY, 0)
    }

    override fun update(executionContext: ExecutionContext) {
        executionContext.putInt(POSITION_KEY, position)
    }

    override fun read(): Int? = if (position < lastItem) ++position else null

    companion object {
        const val POSITION_KEY = "techSmoke.reader.position"
    }
}

class FailOnItemProcessor(private val switch: TechSmokeFailureSwitch) : ItemProcessor<Int, Int> {
    override fun process(item: Int): Int {
        if (switch.mode == FailureMode.PROCESS_ITEM && item == TechSmokeBatchJobConfig.FAILING_ITEM) {
            throw TechSmokeInjectedFailure("injected processor failure at item $item")
        }
        return item
    }
}

class FailAfterWriteWriter(
    private val delegate: ItemWriter<Int>,
    private val switch: TechSmokeFailureSwitch,
) : ItemWriter<Int> {
    override fun write(chunk: Chunk<out Int>) {
        delegate.write(chunk)
        if (switch.mode == FailureMode.AFTER_WRITE && TechSmokeBatchJobConfig.FAILING_ITEM in chunk.items) {
            throw TechSmokeInjectedFailure("injected failure after JDBC write of chunk ${chunk.items}")
        }
    }
}
