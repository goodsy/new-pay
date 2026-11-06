package com.paycore.financial.acquiring.adapter.batch

import com.paycore.financial.acquiring.application.provided.CloseAcquisitionResultsUseCase
import com.paycore.financial.acquiring.application.provided.NextSealDate
import com.paycore.financial.acquiring.application.provided.ReceiveAcquirerResponsesUseCase
import com.paycore.financial.acquiring.application.provided.SealAcquisitionFileUseCase
import com.paycore.financial.acquiring.application.provided.TransmitAcquisitionFileUseCase
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.util.UUID

/**
 * 매입 주기 실행(2-F — Spring `@Scheduled`, payment-api 회복 스케줄러와 같은 관례). 회차마다 lane별로 순서대로:
 * 1. 봉인 — `acquisitionSubmissionJob`(보류 해제 SWEEP → 봉인)을 다음 업무일 JobParameters로 시작(마감이 지난 날만, 회차당 [AcquiringScheduleSettings.maxSealsPerCycle]일까지).
 *    같은 파라미터 재시작은 Batch JobRepository가 실패 step부터 이어 간다(D-ACQ-007). 완료된 인스턴스는 건너뛴다.
 * 2. 전송 — `transmitPending`(SEALED·결과 모름·lease 만료). **봉인을 다시 부르지 않고 저장 봉인 바이트를 보낸다**(리뷰 NIT-1).
 * 3. 결과 수신 — `receive`(ACK·결과 → 수신 축·레코드 결과).
 * 4. 결과 마감 — `closeExpired`(결과 없는 레코드 UNKNOWN — 자동 재전송 없음).
 * 5. 지표 새로 고침.
 * provided Use Case만 부른다(ArchUnit `batchAdapterUsesOnlyApplication`). 단계·lane 하나의 실패는 로그(예외 클래스만)·지표로 남기고 다음 단계를 계속한다.
 * **다중 인스턴스 안전성**은 분산 잠금이 아니라 각 Use Case의 DB 장치가 지킨다: 봉인 = lane 행 잠금 + 저장 바이트 대조(AlreadySealed), 전송 = lease + @Version,
 * 응답·마감 = 파일 행 FOR UPDATE + UNIQUE(이름, hash)·결과 행 PK, Job = JobRepository의 실행 중 인스턴스 거부. Kafka/Redis 없음(ADR-001).
 */
class AcquisitionScheduler(
    private val settings: AcquiringScheduleSettings,
    private val jobOperator: JobOperator,
    private val job: Job,
    private val seal: SealAcquisitionFileUseCase,
    private val transmit: TransmitAcquisitionFileUseCase,
    private val receive: ReceiveAcquirerResponsesUseCase,
    private val close: CloseAcquisitionResultsUseCase,
    private val metrics: AcquiringMetrics,
) {
    private val log = LoggerFactory.getLogger(AcquisitionScheduler::class.java)

    @Scheduled(fixedDelayString = "\${${AcquiringScheduleSettings.PREFIX}.fixed-delay}", initialDelayString = "\${${AcquiringScheduleSettings.PREFIX}.fixed-delay}")
    fun onSchedule() {
        runOnce()
    }

    /** 한 회차(시험은 직접 부른다). 반환 = 단계별 실패 수. */
    fun runOnce(): Int {
        val runId = UUID.randomUUID().toString()
        var failures = 0
        settings.lanes.forEach { lane ->
            failures += step(runId, lane, "seal") { sealDue(lane) }
            failures += step(runId, lane, "transmit") { metrics.countTransmission(transmit.transmitPending(lane.acquirerCode, lane.environment, settings.batchSize)) }
            failures += step(runId, lane, "receive") { metrics.countResponses(receive.receive(lane.acquirerCode, lane.environment)) }
            failures += step(runId, lane, "close") { metrics.countClose(close.closeExpired(lane.acquirerCode, lane.environment, settings.batchSize)) }
        }
        failures += step(runId, null, "metrics") { metrics.refresh() }
        return failures
    }

    private fun sealDue(lane: AcquiringScheduleSettings.Lane) {
        repeat(settings.maxSealsPerCycle) {
            val date = when (val next = seal.nextBusinessDateToSeal(lane.acquirerCode, lane.environment, lane.firstBusinessDate)) {
                is NextSealDate.Due -> next.businessDate
                is NextSealDate.NotDue, NextSealDate.Blocked -> return
                is NextSealDate.NoCutoffPolicy -> {
                    // 리뷰 m-5: 조용히 멈추지 않는다 — 마감 정책 버전이 그 업무일을 덮지 않는다(D-ACQ-003 버전 관리 설정 확인 필요).
                    log.error("acquisition cutoff policy missing for the next business day lane={} businessDate={} — not sealing", lane, next.businessDate)
                    metrics.countSeal(NO_CUTOFF_POLICY)
                    return
                }
            }
            val parameters = JobParametersBuilder()
                .addString(AcquisitionSubmissionJobConfig.PARAM_ACQUIRER, lane.acquirerCode)
                .addString(AcquisitionSubmissionJobConfig.PARAM_ENVIRONMENT, lane.environment)
                .addString(AcquisitionSubmissionJobConfig.PARAM_BUSINESS_DATE, date.toString())
                .toJobParameters()
            val status = try {
                jobOperator.start(job, parameters).status
            } catch (e: JobInstanceAlreadyCompleteException) {
                // 다른 인스턴스(또는 앞 회차)가 이미 끝냈다 — 다음 날짜는 lane이 말한다.
                BatchStatus.COMPLETED
            }
            metrics.countSeal(status.name)
            if (status != BatchStatus.COMPLETED) {
                log.error("acquisition submission job did not complete lane={} businessDate={} status={}", lane, date, status)
                return
            }
        }
    }

    private companion object {
        const val NO_CUTOFF_POLICY = "NO_CUTOFF_POLICY"
    }

    private fun step(runId: String, lane: AcquiringScheduleSettings.Lane?, name: String, block: () -> Unit): Int = try {
        block()
        0
    } catch (e: RuntimeException) {
        log.error("acquisition schedule step failed run={} lane={} step={} failure={}", runId, lane, name, e.javaClass.simpleName)
        1
    } catch (e: Exception) {
        log.error("acquisition schedule step failed run={} lane={} step={} failure={}", runId, lane, name, e.javaClass.simpleName)
        1
    }
}

/**
 * 스케줄러 구성 — `paycore.financial.acquiring.schedule.enabled=true`일 때만 스케줄링을 켜고 Bean을 만든다(기본 비활성 — fail-closed). 설정 값 검사는
 * [AcquiringScheduleSettings] 생성자가 한다(켜졌는데 값이 없으면 기동 실패).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = AcquiringScheduleSettings.PREFIX, name = ["enabled"], havingValue = "true")
@EnableScheduling
@EnableConfigurationProperties(AcquiringScheduleSettings::class)
class AcquisitionScheduleConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun acquisitionScheduler(
        settings: AcquiringScheduleSettings,
        jobOperator: JobOperator,
        @Qualifier(AcquisitionSubmissionJobConfig.JOB_NAME) job: Job,
        seal: SealAcquisitionFileUseCase,
        transmit: TransmitAcquisitionFileUseCase,
        receive: ReceiveAcquirerResponsesUseCase,
        close: CloseAcquisitionResultsUseCase,
        metrics: AcquiringMetrics,
    ) = AcquisitionScheduler(settings, jobOperator, job, seal, transmit, receive, close, metrics)
}
