package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.application.required.AuthorizationTimings
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 승인 전송·회복 시간 설정(`paycore.authorization.timing`, AUTH-001 §4.4·§4.5·§6 — **Demo, 운영값 아님, 운영 기본값 없음**: 없으면 기동 실패). 조회 예산·최대 연령은 D-AUTH-009대로
 * 버전 있는 설정 값이며 코드에 운영 수치가 없다.
 */
@ConfigurationProperties(prefix = "paycore.authorization.timing")
class AuthorizationTimingSettings(val dispatch: Dispatch, val recovery: Recovery) : AuthorizationTimings {

    class Dispatch(val lease: Duration, val safetyMargin: Duration, val preparedDeadline: Duration, val gateRetryInterval: Duration) {
        init {
            listOf(lease, safetyMargin, preparedDeadline, gateRetryInterval).forEach { require(!it.isNegative && !it.isZero) { "dispatch durations must be positive" } }
            require(safetyMargin < lease) { "the safety margin must be shorter than the dispatch lease" }
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
            listOf(fixedDelay, inquiryInterval, maxAge, slowInquiryInterval).forEach { require(!it.isNegative && !it.isZero) { "recovery durations must be positive" } }
            require(batchSize in 1..MAX_BATCH) { "recovery batch size must be 1..$MAX_BATCH" }
            require(inquiryBudget >= 1) { "inquiry budget must be positive" }
            // 검토 #3 m-4: 회복 설정 버전(필수, 운영 기본값 없음) — 일정 행·이관 이벤트에 기록한다.
            require(version.isNotBlank() && version.length <= MAX_VERSION && version.all { it in '!'..'~' }) { "recovery version must be 1..$MAX_VERSION printable ASCII" }
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

/**
 * 기동 검사 AU-34(rev 0.3 m-2): 모든 Route에서 `connectTimeout × (connectRetryBudget + 1) < dispatchLease − safetyMargin`. 전송자는 재연결마다 deadline을 다시 보지만,
 * 설정 자체가 lease 안에 끝날 수 없으면 기동을 거부한다(lease 만료 뒤 늦은 첫 송신 방지).
 */
@Component
class AuthorizationTimingConsistency(
    private val timings: AuthorizationTimingSettings,
    private val routes: ConfiguredInstitutionRouteRegistry,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() = verify()

    fun verify() {
        val window = timings.dispatchLease.minus(timings.safetyMargin)
        routes.routes.forEach { (routeRef, r) ->
            val connectBudget = r.connectTimeout.multipliedBy((r.connectRetryBudget + 1).toLong())
            check(connectBudget < window) { "AU-34: route $routeRef connect budget does not fit in dispatch lease − safety margin" }
            // 검토 #3 m-2: 보존기간 안에서 이관하고(maxAge), 종결 요청은 보존기간 안·전송이 끝났을 수 있는 시간 뒤에만.
            val c = r.capabilities
            check(timings.maxAge < c.inquiryRetention) { "m-2: recovery maxAge must be shorter than route $routeRef inquiry retention" }
            check(c.closableAfter < c.inquiryRetention) { "m-2: route $routeRef closableAfter must be shorter than its inquiry retention" }
            check(c.closableAfter >= timings.preparedDeadline.plus(timings.dispatchLease)) {
                "m-2: route $routeRef closableAfter must be at least preparedDeadline + dispatchLease"
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthorizationTimingSettings::class)
class AuthorizationTimingConfiguration
