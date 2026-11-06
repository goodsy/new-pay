package com.paycore.financial.acquiring.adapter.batch

import com.paycore.financial.acquiring.application.provided.SealAcquisitionFileResult
import com.paycore.financial.acquiring.application.provided.SealAcquisitionFileUseCase
import com.paycore.financial.paymentfact.application.provided.SweepOriginalUnknownHoldsUseCase
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDate

/**
 * 매입 제출 Job(ACQUIRING-001 §4.2 — 배치1: 보류 해제 일괄 처리 → 봉인. SFTP 전송 step은 배치2). INV-09: Batch는 실행 조정만 하고 업무 규칙은 Application Use Case
 * (`application.provided`)가 가진다. 두 Use Case는 각자 새 FINANCIAL 로컬 TX(REQUIRES_NEW)로 커밋하므로 step TX(metadata)와 업무 효과는 따로 커밋된다 —
 * step이 업무 커밋 뒤 실패해도 같은 JobParameters 재시작이 같은 의도를 멱등하게 다시 실행한다(SWEEP = UNIQUE(hold_id), 봉인 = 저장 바이트 대조 AlreadySealed).
 * Job 재시작 성공은 외부 전송 exactly-once를 뜻하지 않는다(TECH §4 — 전송은 배치2의 같은 파일 ID 규칙이 지킨다).
 *
 * 식별 JobParameters: `acquirerCode`·`environment`·`businessDate`(yyyy-MM-dd). 자동 기동 없음(spring.batch.job.enabled=false) — 실행은 운영 절차/시험이 명시한다
 * (스케줄러는 전송과 함께 배치2).
 */
@Configuration(proxyBeanMethods = false)
class AcquisitionSubmissionJobConfig {

    @Bean(JOB_NAME)
    fun acquisitionSubmissionJob(jobRepository: JobRepository, holdReleaseSweepStep: Step, sealAcquisitionFileStep: Step): Job =
        JobBuilder(JOB_NAME, jobRepository).start(holdReleaseSweepStep).next(sealAcquisitionFileStep).build()

    @Bean
    fun holdReleaseSweepStep(jobRepository: JobRepository, transactionManager: PlatformTransactionManager, sweep: SweepOriginalUnknownHoldsUseCase): Step =
        StepBuilder("$JOB_NAME.holdReleaseSweep", jobRepository).tasklet(
            Tasklet { contribution, _ ->
                val result = sweep.sweep(SWEEP_BATCH)
                contribution.stepExecution.executionContext.putInt("released", result.released)
                contribution.stepExecution.executionContext.putInt("reviewRequired", result.reviewRequired)
                // 한 회차에 SWEEP_BATCH개를 넘게 남았으면 다음 회차가 이어서 처리한다(UNIQUE로 멱등).
                RepeatStatus.FINISHED
            },
            transactionManager,
        ).build()

    @Bean
    fun sealAcquisitionFileStep(jobRepository: JobRepository, transactionManager: PlatformTransactionManager, seal: SealAcquisitionFileUseCase): Step =
        StepBuilder("$JOB_NAME.seal", jobRepository).tasklet(
            Tasklet { contribution, chunkContext ->
                val parameters = chunkContext.stepContext.stepExecution.jobParameters
                val acquirerCode = requireNotNull(parameters.getString(PARAM_ACQUIRER)) { "job parameter $PARAM_ACQUIRER is required" }
                val environment = requireNotNull(parameters.getString(PARAM_ENVIRONMENT)) { "job parameter $PARAM_ENVIRONMENT is required" }
                val businessDate = LocalDate.parse(requireNotNull(parameters.getString(PARAM_BUSINESS_DATE)) { "job parameter $PARAM_BUSINESS_DATE is required" })
                val result = seal.seal(acquirerCode, environment, businessDate)
                contribution.stepExecution.executionContext.putString("fileId", result.fileId)
                contribution.stepExecution.executionContext.putString("contentSha256", result.contentSha256)
                contribution.stepExecution.executionContext.putString("outcome", if (result is SealAcquisitionFileResult.Sealed) "SEALED" else "ALREADY_SEALED")
                RepeatStatus.FINISHED
            },
            transactionManager,
        ).build()

    companion object {
        const val JOB_NAME = "acquisitionSubmissionJob"
        const val PARAM_ACQUIRER = "acquirerCode"
        const val PARAM_ENVIRONMENT = "environment"
        const val PARAM_BUSINESS_DATE = "businessDate"
        const val SWEEP_BATCH = 500
    }
}
