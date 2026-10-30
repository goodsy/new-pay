package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.AdminAccountMetrics
import com.paycore.admin.account.application.required.AdminClock
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * account 보안 지표(Micrometer — master-api·payment-api `Micrometer…Metrics` 관례, adapter.integration). label은 분류값만.
 * 경보 기준(운영 설정 — 이 저장소에는 경보 시스템 없음): [AUTHENTICATION_FAILURE_NOT_RECORDED] 증가 1건 이상(D-ADMIN1-016), [LOCKOUT] 급증,
 * [ACTIVE_ROLE_HOLDERS]`{role=SECURITY_ADMIN|APPROVER}` 2 미만(D-ADMIN1-023 — LIVE Gate 운영 하한, 계열 없음 = 갱신 job 미동작도 경보),
 * 갱신 정체: [ACTIVE_ROLE_REFRESH_AGE] > 갱신 주기 × 3(Demo 3분) 또는 계열 없음, [ACTIVE_ROLE_REFRESH]`{result=FAILURE}` 증가(리뷰 Minor-2).
 */
@Component
class MicrometerAdminAccountMetrics(private val registry: MeterRegistry, private val clock: AdminClock) : AdminAccountMetrics {
    override fun login(step: String, result: String) {
        registry.counter(LOGIN, "step", step, "result", result).increment()
    }

    override fun lockout(step: String) {
        registry.counter(LOCKOUT, "step", step).increment()
    }

    override fun authenticationFailureNotRecorded(step: String, failure: String) {
        registry.counter(AUTHENTICATION_FAILURE_NOT_RECORDED, "step", step, "failure", failure).increment()
    }

    override fun enrollment(operation: String, result: String) {
        registry.counter(ENROLLMENT, "operation", operation, "result", result).increment()
    }

    override fun oneShot(command: String, result: String) {
        registry.counter(ONE_SHOT, "command", command, "result", result).increment()
    }

    /** 첫 갱신 때 등록(기동 직후 갱신 전에는 계열 없음 — 0으로 오경보하지 않는다). Micrometer gauge는 약한 참조라 값 객체를 이 map이 붙잡는다. */
    override fun activeRoleHolders(role: String, count: Int) {
        activeRoleGauges.computeIfAbsent(role) { r -> requireNotNull(registry.gauge(ACTIVE_ROLE_HOLDERS, listOf(Tag.of("role", r)), AtomicLong(0))) }.set(count.toLong())
    }

    private val activeRoleGauges = ConcurrentHashMap<String, AtomicLong>()

    override fun activeRoleRefresh(result: String) {
        registry.counter(ACTIVE_ROLE_REFRESH, "result", result).increment()
    }

    /**
     * 마지막 성공 시각을 기록하고, 첫 성공 때 경과 gauge를 등록한다. 경과는 **수집 시점**에 [clock]으로 계산한다 — 갱신 job이 멈추거나 실패만 이어져도
     * 값이 계속 늘어 "갱신 정체" 경보가 된다(리뷰 Minor-2). 첫 성공 전에는 계열이 없다(부재 = 경보 대상).
     */
    override fun activeRoleRefreshSucceeded(at: Instant) {
        lastRefreshSuccessMillis.set(at.toEpochMilli())
        if (refreshAgeRegistered.compareAndSet(false, true)) {
            Gauge.builder(ACTIVE_ROLE_REFRESH_AGE, lastRefreshSuccessMillis) { last -> (clock.now().toEpochMilli() - last.get()).coerceAtLeast(0) / 1000.0 }
                .strongReference(true).register(registry)
        }
    }

    private val lastRefreshSuccessMillis = AtomicLong(0)
    private val refreshAgeRegistered = AtomicBoolean(false)

    companion object {
        const val LOGIN = "paycore.admin.login"
        const val LOCKOUT = "paycore.admin.lockout"
        const val AUTHENTICATION_FAILURE_NOT_RECORDED = "paycore.admin.authentication_failure.not_recorded"
        const val ENROLLMENT = "paycore.admin.enrollment"
        const val ONE_SHOT = "paycore.admin.oneshot"
        const val ACTIVE_ROLE_HOLDERS = "paycore.admin.accounts.active"
        const val ACTIVE_ROLE_REFRESH = "paycore.admin.accounts.active.refresh"
        const val ACTIVE_ROLE_REFRESH_AGE = "paycore.admin.accounts.active.refresh.age_seconds"
    }
}
