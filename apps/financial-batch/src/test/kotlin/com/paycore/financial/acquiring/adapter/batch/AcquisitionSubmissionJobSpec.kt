package com.paycore.financial.acquiring.adapter.batch

import com.paycore.financial.FinancialMySqlContainerConfig
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.application.PaymentFactInboxService
import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDate

/**
 * 매입 제출 Job(배치1 — 보류 해제 일괄 처리 → 봉인) 실제 MySQL·JDBC JobRepository 시험(AQ-J1..J2, runtime 계정). 운영 Bean(시스템 시계)을 그대로 쓰므로
 * 업무일은 이미 마감이 지난 과거 날짜(2026-10-01 — Demo 정책 시행 2026-01-01 이후)로 둔다. Job 재시작 성공은 외부 전송 exactly-once를 뜻하지 않는다(전송은 배치2).
 */
@SpringBootTest
@Import(FinancialMySqlContainerConfig::class)
class AcquisitionSubmissionJobSpec(
    private val jobOperator: JobOperator,
    @Qualifier(AcquisitionSubmissionJobConfig.JOB_NAME) private val job: Job,
    private val inbox: PaymentFactInboxService,
    private val jdbc: JdbcTemplate,
) : BehaviorSpec({

    val day = LocalDate.parse("2026-10-01")

    fun params(acquirer: String, date: LocalDate): JobParameters = JobParametersBuilder()
        .addString(AcquisitionSubmissionJobConfig.PARAM_ACQUIRER, acquirer)
        .addString(AcquisitionSubmissionJobConfig.PARAM_ENVIRONMENT, "TEST")
        .addString(AcquisitionSubmissionJobConfig.PARAM_BUSINESS_DATE, date.toString())
        .toJobParameters()

    Given("AQ-J1 a lane with one approval and an early cancel whose original arrived (released) on the business day") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        val (auth, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 2_500, acquirerCode = acq, approvedAt = Instant.parse("2026-10-01T01:00:00.000Z"))
        inbox.receive(PaymentFactEnvelopeFixture.inbound(cancel))
        inbox.receive(PaymentFactEnvelopeFixture.inbound(auth))
        val execution = jobOperator.start(job, params(acq, day))
        Then("the job completes both steps and seals the day's file with the netted record; the step context records the file id and hash") {
            execution.status shouldBe BatchStatus.COMPLETED
            execution.stepExecutions.map { it.stepName to it.status } shouldBe listOf(
                "${AcquisitionSubmissionJobConfig.JOB_NAME}.holdReleaseSweep" to BatchStatus.COMPLETED,
                "${AcquisitionSubmissionJobConfig.JOB_NAME}.seal" to BatchStatus.COMPLETED,
            )
            val seal = execution.stepExecutions.single { it.stepName.endsWith(".seal") }.executionContext
            seal.getString("fileId") shouldBe "ACQ.$acq.TEST.20261001.001"
            seal.getString("outcome") shouldBe "SEALED"
            jdbc.queryForObject("SELECT content_sha256 FROM acquisition_file WHERE file_id = ?", String::class.java, seal.getString("fileId")) shouldBe
                seal.getString("contentSha256")
            jdbc.queryForObject("SELECT record_amount_minor FROM acquisition_file_record WHERE transaction_id = ?", Long::class.java, auth.transactionId) shouldBe 7_500L
        }
    }

    Given("AQ-J2 a job for a later day fails out of order, the earlier day is sealed, and the failed job is restarted with the same parameters") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        inbox.receive(PaymentFactEnvelopeFixture.inbound(PaymentFactFixture.authorized(acquirerCode = acq, approvedAt = Instant.parse("2026-10-01T02:00:00.000Z"))))
        jobOperator.start(job, params(acq, day)).status shouldBe BatchStatus.COMPLETED
        val skipped = day.plusDays(2)
        val failed = jobOperator.start(job, params(acq, skipped))
        jobOperator.start(job, params(acq, day.plusDays(1))).status shouldBe BatchStatus.COMPLETED
        val restarted = jobOperator.start(job, params(acq, skipped))
        Then("the first attempt FAILED in the seal step (OUT_OF_ORDER — nothing written); the restart of the same job instance COMPLETED and sealed the day once") {
            failed.status shouldBe BatchStatus.FAILED
            failed.stepExecutions.single { it.stepName.endsWith(".seal") }.status shouldBe BatchStatus.FAILED
            restarted.status shouldBe BatchStatus.COMPLETED
            restarted.jobInstance.instanceId shouldBe failed.jobInstance.instanceId
            jdbc.queryForObject("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ?", Int::class.java, acq) shouldBe 3
            jdbc.queryForObject("SELECT last_sealed_business_date FROM acquisition_lane WHERE acquirer_code = ?", LocalDate::class.java, acq) shouldBe skipped
        }
    }
})
