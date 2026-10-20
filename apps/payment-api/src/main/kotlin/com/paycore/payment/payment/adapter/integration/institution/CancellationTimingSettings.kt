package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.application.required.CancellationTimings
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 취소 전송·회복 시간 설정(`paycore.cancellation.timing`, CANCEL-001 CN-29 — **Demo, 운영값 아님, 운영 기본값 없음**: 없으면 기동 실패). 승인 설정(`paycore.authorization.timing`)과
 * 같은 모양·같은 검사이며 값·버전은 따로 둔다(D-AUTH-009 — 두 operation의 설정 버전을 섞지 않는다, §7.1 선택 A). Route 취소 Capability와의 기동 일관성 검사는 회복(S7)과 함께 둔다.
 */
@ConfigurationProperties(prefix = "paycore.cancellation.timing")
class CancellationTimingSettings(val dispatch: Dispatch, val recovery: Recovery) : CancellationTimings {

    class Dispatch(val lease: Duration, val safetyMargin: Duration, val preparedDeadline: Duration, val gateRetryInterval: Duration) {
        init {
            listOf(lease, safetyMargin, preparedDeadline, gateRetryInterval).forEach { require(!it.isNegative && !it.isZero) { "cancel dispatch durations must be positive" } }
            require(safetyMargin < lease) { "the cancel safety margin must be shorter than the dispatch lease" }
        }
    }

    class Recovery(
        val enabled: Boolean,
        val fixedDelay: Duration,
        val batchSize: Int,
        val inquiryInterval: Duration,
        val inquiryBudget: Int,
        val maxAge: Duration,
        val slowInquiryInterval: Duration,
        val version: String,
    ) {
        init {
            listOf(fixedDelay, inquiryInterval, maxAge, slowInquiryInterval).forEach { require(!it.isNegative && !it.isZero) { "cancel recovery durations must be positive" } }
            require(batchSize in 1..MAX_BATCH) { "cancel recovery batch size must be 1..$MAX_BATCH" }
            require(inquiryBudget >= 1) { "cancel inquiry budget must be positive" }
            require(version.isNotBlank() && version.length <= MAX_VERSION && version.all { it in '!'..'~' }) { "cancel recovery version must be 1..$MAX_VERSION printable ASCII" }
        }
    }

    override val dispatchLease: Duration get() = dispatch.lease
    override val safetyMargin: Duration get() = dispatch.safetyMargin
    override val preparedDeadline: Duration get() = dispatch.preparedDeadline
    override val gateRetryInterval: Duration get() = dispatch.gateRetryInterval
    override val inquiryInterval: Duration get() = recovery.inquiryInterval
    override val inquiryBudget: Int get() = recovery.inquiryBudget
    override val maxAge: Duration get() = recovery.maxAge
    override val slowInquiryInterval: Duration get() = recovery.slowInquiryInterval
    override val recoveryBatchSize: Int get() = recovery.batchSize
    override val recoveryConfigVersion: String get() = recovery.version

    private companion object {
        const val MAX_BATCH = 1000
        const val MAX_VERSION = 32
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CancellationTimingSettings::class)
class CancellationTimingConfiguration
