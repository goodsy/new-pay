package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AuthenticationFailureOutcome
import com.paycore.admin.account.application.provided.AuthenticationFailureRecorder
import com.paycore.admin.account.application.provided.AuthenticationStep
import com.paycore.admin.account.application.required.AdminAccountMetrics
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminClock
import com.paycore.admin.account.application.required.AdminSecuritySettings
import com.paycore.admin.account.application.required.PersistenceFailure
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AuthenticationFailure
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.temporal.ChronoUnit

/**
 * [AuthenticationFailureRecorder] — 독립 로컬 TX: 계정 행 `FOR UPDATE` → domain 계수(ACTIVE만, 한도면 LOCKED) → 갱신 → 감사 → 커밋.
 * 같은 계정의 동시 실패는 행 잠금으로 직렬화되어 하나도 잃지 않는다(LoginLockoutConcurrency). 1213·1205는 새 TX에서 재시도(최대 3회).
 * 없는 계정은 계수 대상이 아니다(없는 계정의 감사 — HMAC login_name — 는 로그인 흐름 S4가 한다).
 */
@Service
class AuthenticationFailureService(
    transactionManager: PlatformTransactionManager,
    private val accounts: AdminAccountRepository,
    private val clock: AdminClock,
    private val settings: AdminSecuritySettings,
    recorder: AdminAuditRecorder,
    private val metrics: AdminAccountMetrics,
) : AuthenticationFailureRecorder {

    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val audit = AccountAuditWriter(recorder)

    override fun recordFailure(actorId: String, step: AuthenticationStep, context: AdminRequestContext): AuthenticationFailureOutcome {
        val id = try {
            AdminActorId(actorId)
        } catch (_: IllegalArgumentException) {
            return AuthenticationFailureOutcome.UNKNOWN_ACCOUNT
        }
        val stepLabel = if (step == AuthenticationStep.LOGIN_TOTP) "TOTP" else step.name
        return try {
            transactions.execute({ it is PersistenceFailure.Deadlock || it is PersistenceFailure.LockTimeout }) {
            val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
            val current = accounts.lock(id) ?: return@execute AuthenticationFailureOutcome.UNKNOWN_ACCOUNT
            val failure = current.value.recordAuthenticationFailure(settings.loginAttemptPolicy, now)
            val outcome = when (failure) {
                is AuthenticationFailure.Counted -> AuthenticationFailureOutcome.COUNTED
                is AuthenticationFailure.Locked -> AuthenticationFailureOutcome.LOCKED
                is AuthenticationFailure.NotCounted -> AuthenticationFailureOutcome.NOT_COUNTED
            }
            if (failure !is AuthenticationFailure.NotCounted) accounts.update(current, failure.account)
            val detail = "step=${step.name};failures=${failure.account.failedAttempts}"
            audit.admin(now, id.value, current.value, AUTHENTICATION_FAILED, outcome.name, context, id.value, detail)
            if (failure is AuthenticationFailure.Locked) audit.admin(now, id.value, current.value, ACCOUNT_LOCKED, AccountAuditWriter.SUCCESS, context, id.value, detail)
            outcome
            }
        } catch (e: RuntimeException) {
            // D-ADMIN1-016: 재시도 3회 뒤에도 계수 TX가 실패하면 그 1회는 계수되지 않는다(현재 유지) — 경보 대상 지표로 관측한다.
            metrics.authenticationFailureNotRecorded(
                stepLabel,
                when (e) {
                    is PersistenceFailure.Deadlock -> "DEADLOCK"
                    is PersistenceFailure.LockTimeout -> "LOCK_TIMEOUT"
                    else -> "OTHER"
                },
            )
            throw e
        }.also { if (it == AuthenticationFailureOutcome.LOCKED) metrics.lockout(stepLabel) }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED"
        const val ACCOUNT_LOCKED = "ACCOUNT_LOCKED"
    }
}
