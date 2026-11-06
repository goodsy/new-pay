package com.paycore.financial.acquiring.adapter.batch

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.LocalDate

/**
 * 매입 주기 실행 설정(2-F — 봉인 → 전송 → 결과 수신 → 결과 마감). **기본 비활성(fail-closed)**: `enabled`가 true가 아니면 스케줄러 Bean 자체가 없다. 켜면 모든 값이 필수이고
 * 형식 밖이면 기동 실패. 값은 Demo 값이다(주기·lane·첫 업무일 — 운영값은 LIVE Gate §14-4). LIVE lane은 받지 않는다(D-ACQ-008).
 *
 * @param fixedDelay 회차 사이 간격(1초..1일). 첫 회차도 기동 뒤 이만큼 기다린다.
 * @param lanes `{acquirer-code([A-Z0-9]{1,32}), environment(TEST), first-business-date(yyyy-MM-dd — 봉인 이력이 없을 때 첫 업무일)}` 1개 이상.
 * @param batchSize 전송·결과 마감 회차당 파일 수(1..100).
 * @param maxSealsPerCycle 회차당 lane별 봉인 업무일 수(밀린 날을 따라잡는 상한, 1..31).
 */
@ConfigurationProperties(prefix = "paycore.financial.acquiring.schedule")
class AcquiringScheduleSettings(
    val enabled: Boolean = false,
    fixedDelay: Duration?,
    lanes: List<Lane>?,
    batchSize: Int?,
    maxSealsPerCycle: Int?,
) {
    class Lane(val acquirerCode: String, val environment: String, val firstBusinessDate: LocalDate) {
        override fun toString(): String = "$acquirerCode/$environment"
    }

    val fixedDelay: Duration = if (!enabled) Duration.ZERO else requireNotNull(fixedDelay) { "$PREFIX.fixed-delay is required when enabled" }.also {
        require(it >= Duration.ofSeconds(1) && it <= Duration.ofDays(1)) { "$PREFIX.fixed-delay must be within PT1S..P1D" }
    }

    val lanes: List<Lane> = if (!enabled) emptyList() else requireNotNull(lanes) { "$PREFIX.lanes is required when enabled" }.also { list ->
        require(list.isNotEmpty()) { "$PREFIX.lanes must not be empty when enabled" }
        list.forEach { lane ->
            require(CODE.matches(lane.acquirerCode)) { "$PREFIX.lanes[].acquirer-code must match [A-Z0-9]{1,32}" }
            // D-ACQ-008: LIVE 매입 차단 — 스케줄 설정에서도 받지 않는다(봉인·전송·수신 Use Case도 각각 거부한다).
            require(lane.environment == "TEST") { "$PREFIX.lanes[].environment must be TEST (LIVE acquisition is blocked — D-ACQ-008)" }
        }
        require(list.map { it.acquirerCode to it.environment }.toSet().size == list.size) { "$PREFIX.lanes must be distinct" }
    }

    val batchSize: Int = if (!enabled) 0 else requireNotNull(batchSize) { "$PREFIX.batch-size is required when enabled" }.also {
        require(it in 1..MAX_BATCH) { "$PREFIX.batch-size must be within 1..$MAX_BATCH" }
    }

    val maxSealsPerCycle: Int = if (!enabled) 0 else requireNotNull(maxSealsPerCycle) { "$PREFIX.max-seals-per-cycle is required when enabled" }.also {
        require(it in 1..MAX_SEALS) { "$PREFIX.max-seals-per-cycle must be within 1..$MAX_SEALS" }
    }

    override fun toString(): String = "AcquiringScheduleSettings(enabled=$enabled, fixedDelay=$fixedDelay, lanes=$lanes)"

    companion object {
        const val PREFIX = "paycore.financial.acquiring.schedule"
        private const val MAX_BATCH = 100
        private const val MAX_SEALS = 31
        private val CODE = Regex("^[A-Z0-9]{1,32}$")
    }
}
