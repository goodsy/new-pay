package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.AdminAccountMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * account 보안 지표(Micrometer — master-api·payment-api `Micrometer…Metrics` 관례, adapter.integration). label은 분류값만.
 * 경보 기준(운영 설정 — 이 저장소에는 경보 시스템 없음): [AUTHENTICATION_FAILURE_NOT_RECORDED] 증가 1건 이상(D-ADMIN1-016), [LOCKOUT] 급증.
 */
@Component
class MicrometerAdminAccountMetrics(private val registry: MeterRegistry) : AdminAccountMetrics {
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

    companion object {
        const val LOGIN = "paycore.admin.login"
        const val LOCKOUT = "paycore.admin.lockout"
        const val AUTHENTICATION_FAILURE_NOT_RECORDED = "paycore.admin.authentication_failure.not_recorded"
        const val ENROLLMENT = "paycore.admin.enrollment"
        const val ONE_SHOT = "paycore.admin.oneshot"
    }
}
