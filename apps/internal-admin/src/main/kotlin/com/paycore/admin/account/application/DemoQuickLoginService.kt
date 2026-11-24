package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminRetryLaterException
import com.paycore.admin.account.application.provided.DemoQuickLoginOption
import com.paycore.admin.account.application.provided.DemoQuickLoginResult
import com.paycore.admin.account.application.provided.DemoQuickLoginUseCase
import com.paycore.admin.account.application.required.AdminAccountMetrics
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminClock
import com.paycore.admin.account.application.required.AdminSecuritySettings
import com.paycore.admin.account.application.required.DemoQuickLoginSettings
import com.paycore.admin.account.application.required.LoginNameDigester
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.LoginName
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [DemoQuickLoginUseCase](ADMIN-DEMO-LOGIN-001 §4·§5·§6). 감사 행위: 성공 `LOGIN_SUCCEEDED`·`SUCCESS`·`method=DEMO_QUICK`(일반 로그인 성공과 같은 action — 방식은 detail),
 * 거부 `LOGIN_DEMO_QUICK` — 허용 목록 밖·없는 계정·형식 오류는 ANONYMOUS + login_name 비밀 키 HMAC `REJECTED reason=NOT_ALLOWED`,
 * 그 계정이 ACTIVE가 아니면 `NOT_COUNTED reason=NOT_ACTIVE`, 실패 한도 도달이면 `NOT_COUNTED reason=LIMIT`. 지표 `step=DEMO_QUICK`.
 */
@Service
class DemoQuickLoginService(
    transactionManager: PlatformTransactionManager,
    private val accounts: AdminAccountRepository,
    private val digester: LoginNameDigester,
    private val clock: AdminClock,
    private val settings: AdminSecuritySettings,
    private val quick: DemoQuickLoginSettings,
    private val recorder: AdminAuditRecorder,
    private val metrics: AdminAccountMetrics,
) : DemoQuickLoginUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)
    private val reads = LocalTransactions(transactionManager, maxAttempts = 1, readOnly = true)

    override fun isActive(): Boolean = quick.active

    override fun options(): List<DemoQuickLoginOption> {
        if (!quick.active) return emptyList()
        return reads.execute({ false }) {
            quick.allowedLoginNames.mapNotNull { name ->
                val account = loginNameOf(name)?.let { accounts.findByLoginName(it) }?.value ?: return@mapNotNull null
                DemoQuickLoginOption(account.loginName.value, account.displayName.value, account.activeRoles.map { it.name }.sorted(), usable(account))
            }
        }
    }

    override fun login(loginName: String, context: AdminRequestContext): DemoQuickLoginResult {
        if (!quick.active) return DemoQuickLoginResult.Rejected
        var result = REJECTED
        val outcome = try {
            retryLaterOnConflict {
                transactions.execute({ false }) {
                    val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
                    val name = loginNameOf(loginName)?.takeIf { it.value in quick.allowedLoginNames }
                    val current = name?.let { accounts.findByLoginName(it) }?.let { accounts.lock(it.value.id) }
                    if (current == null) {
                        anonymous(now, loginName, context)
                        return@execute DemoQuickLoginResult.Rejected
                    }
                    val account = current.value
                    when {
                        // 리뷰 NIT-2: 일반 TOTP 성공 경로의 `credential == null` 거부와 같은 조건을 명시한다. ACTIVE면 비밀번호·TOTP가 있다는 domain 불변식(AdminAccount init)에
                        // 기대지 않고, 등록이 끝나지 않은 계정(자격 없음)은 상태와 관계없이 NOT_ACTIVE로 거부한다.
                        account.status != AccountStatus.ACTIVE || account.passwordHash == null || account.totp == null -> {
                            result = NOT_COUNTED
                            audit(now, account, REFUSED_ACTION, NOT_COUNTED, context, "reason=NOT_ACTIVE")
                            DemoQuickLoginResult.Rejected
                        }
                        account.hasReachedFailureLimit(settings.loginAttemptPolicy) -> {
                            result = NOT_COUNTED
                            audit(now, account, REFUSED_ACTION, NOT_COUNTED, context, "reason=LIMIT")
                            DemoQuickLoginResult.Rejected
                        }
                        else -> {
                            val next = account.startNewSession(now)
                            accounts.update(current, next)
                            result = SUCCESS
                            audit(now, account, SUCCEEDED_ACTION, SUCCESS, context, METHOD)
                            DemoQuickLoginResult.Authenticated(account.id.value, next.sessionGeneration, next.securityVersion, now, settings.authenticatedSessionIdle)
                        }
                    }
                }
            }
        } catch (e: AdminRetryLaterException) {
            metrics.login(STEP, RETRY_LATER)
            throw e
        } catch (e: RuntimeException) {
            metrics.login(STEP, ERROR)
            throw e
        }
        metrics.login(STEP, result)
        return outcome
    }

    private fun usable(account: AdminAccount): Boolean = account.status == AccountStatus.ACTIVE && !account.hasReachedFailureLimit(settings.loginAttemptPolicy)

    private fun loginNameOf(value: String): LoginName? = if (LoginName.FORMAT.matches(value)) LoginName(value) else null

    @Suppress("LongParameterList")
    private fun audit(now: Instant, account: AdminAccount, action: String, resultCode: String, context: AdminRequestContext, detail: String) = recorder.record(
        AuditRecord(
            occurredAt = now, actorKind = "ADMIN", actorId = account.id.value, actorDisplayName = account.displayName.value, action = action, resultCode = resultCode,
            correlationId = context.correlationId, targetActorId = account.id.value, remoteIp = context.remoteIp, detail = detail,
        ),
    )

    private fun anonymous(now: Instant, loginName: String, context: AdminRequestContext) = recorder.record(
        AuditRecord(
            occurredAt = now, actorKind = "ANONYMOUS", actorId = null, actorDisplayName = null, action = REFUSED_ACTION, resultCode = REJECTED,
            correlationId = context.correlationId, remoteIp = context.remoteIp, loginNameHmac = digester.digest(loginName), detail = "reason=NOT_ALLOWED",
        ),
    )

    private companion object {
        const val SUCCEEDED_ACTION = "LOGIN_SUCCEEDED"
        const val REFUSED_ACTION = "LOGIN_DEMO_QUICK"
        const val METHOD = "method=DEMO_QUICK"
        const val SUCCESS = "SUCCESS"
        const val REJECTED = "REJECTED"
        const val NOT_COUNTED = "NOT_COUNTED"
        const val STEP = "DEMO_QUICK"
        const val RETRY_LATER = "RETRY_LATER"
        const val ERROR = "ERROR"
    }
}
