package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AdminLoginUseCase
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminRetryLaterException
import com.paycore.admin.account.application.provided.PasswordStepResult
import com.paycore.admin.account.application.provided.PasswordVerified
import com.paycore.admin.account.application.provided.TotpStepResult
import com.paycore.admin.account.application.required.AdminAccountMetrics
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminClock
import com.paycore.admin.account.application.required.AdminCredentialReader
import com.paycore.admin.account.application.required.AdminSecuritySettings
import com.paycore.admin.account.application.required.CipherContext
import com.paycore.admin.account.application.required.LoginNameDigester
import com.paycore.admin.account.application.required.PasswordHasher
import com.paycore.admin.account.application.required.SealedSecret
import com.paycore.admin.account.application.required.TotpSecretCipher
import com.paycore.admin.account.application.required.TotpVerifier
import com.paycore.admin.account.domain.AccountChange
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AuthenticationFailure
import com.paycore.admin.account.domain.LoginName
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [AdminLoginUseCase](§4.3.2). 비밀번호 단계의 모든 분기(성공·틀림·없는 계정·LOCKED·SUSPENDED·PENDING·한도 도달·자격 변경 경합)는 BCrypt 비교 1회·로컬 TX 1회·
 * 감사 1행으로 같다(경로 동등성 — 잔여 시간차는 §4.3.2 n-G 수용 근거). 실패 응답은 하나(`InvalidCredentials`).
 * 감사 행위: `LOGIN_PASSWORD`(SUCCESS·FAILED·LOCKED·NOT_COUNTED·REJECTED `reason=STALE`), `LOGIN_TOTP`(FAILED·LOCKED·REJECTED `reason=STALE`), `LOGIN_SUCCEEDED`, `LOGOUT`.
 * 알려진 계정은 행위자 = 그 계정(주장된 신원), 없는 계정은 ANONYMOUS + login_name 비밀 키 HMAC(n-2).
 */
@Service
class AdminLoginService(
    transactionManager: PlatformTransactionManager,
    private val credentials: AdminCredentialReader,
    private val accounts: AdminAccountRepository,
    private val hasher: PasswordHasher,
    private val cipher: TotpSecretCipher,
    private val verifier: TotpVerifier,
    private val digester: LoginNameDigester,
    private val clock: AdminClock,
    private val settings: AdminSecuritySettings,
    private val recorder: AdminAuditRecorder,
    private val metrics: AdminAccountMetrics,
) : AdminLoginUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    override fun verifyPassword(loginName: String, password: CharArray, context: AdminRequestContext): PasswordStepResult {
        // (a) 비잠금 읽기(TX 밖) + BCrypt 비교 정확히 1회(없는 계정·자격 없는 계정은 같은 cost 더미 해시 — PasswordHasher 계약).
        val stored = if (LoginName.FORMAT.matches(loginName)) credentials.findByLoginName(loginName) else null
        // 바이트 상한 초과는 저장 해시와 비교하지 않고 같은 cost 더미 비교 1회(분기 동등 — S4 리뷰 m-3). 틀린 비밀번호로 센다(상한 안의 값만 설정 가능하므로 일치 불가).
        val tooLong = EnrollmentService.utf8Length(password) > settings.passwordMaxBytes
        val matched = hasher.matches(if (tooLong) EMPTY else password, if (tooLong) null else stored?.passwordHash) && !tooLong
        // (b) 로컬 TX 1회: 잠금 → 상태·security_version 먼저 → BCrypt 결과 적용 → 감사 1행. 지표는 TX가 끝난 뒤 감사와 같은 결과 분류로(S8).
        val label = StepLabel(UNKNOWN_ACCOUNT)
        return observed(PASSWORD_STEP, label) { retryLaterOnConflict {
            transactions.execute({ false }) {
                val now = now()
                val current = stored?.let { accounts.lock(it.actorId) }
                if (stored == null || current == null) {
                    label.result = UNKNOWN_ACCOUNT
                    anonymous(now, loginName, context)
                    return@execute PasswordStepResult.InvalidCredentials
                }
                val account = current.value
                when {
                    account.status != AccountStatus.ACTIVE -> {
                        audit(now, account, PASSWORD, NOT_COUNTED, context, "reason=NOT_ACTIVE", label)
                        PasswordStepResult.InvalidCredentials
                    }
                    account.securityVersion != stored.securityVersion -> {
                        audit(now, account, PASSWORD, REJECTED, context, STALE, label)
                        PasswordStepResult.InvalidCredentials
                    }
                    account.hasReachedFailureLimit(settings.loginAttemptPolicy) -> {
                        audit(now, account, PASSWORD, NOT_COUNTED, context, "reason=LIMIT", label)
                        PasswordStepResult.InvalidCredentials
                    }
                    matched -> {
                        audit(now, account, PASSWORD, SUCCESS, context, null, label)
                        PasswordStepResult.Verified(
                            PasswordVerified(account.id.value, account.securityVersion, now.plus(settings.passwordStepIdle)), settings.passwordStepIdle,
                        )
                    }
                    else -> {
                        val failure = account.recordAuthenticationFailure(settings.loginAttemptPolicy, now)
                        accounts.update(current, failure.account)
                        audit(now, account, PASSWORD, if (failure is AuthenticationFailure.Locked) LOCKED else FAILED, context, "failures=${failure.account.failedAttempts}", label)
                        PasswordStepResult.InvalidCredentials
                    }
                }
            }
        } }
    }

    override fun verifyTotp(verified: PasswordVerified, code: String, context: AdminRequestContext): TotpStepResult {
        val id = runCatching { AdminActorId(verified.actorId) }.getOrNull() ?: return TotpStepResult.Rejected(endSession = true)
        // 단계 만료·형식 오류는 TOTP 검증 전 거절: 계수 없이 REJECTED 감사 1행(S4 리뷰 nit).
        val early = when {
            !clock.now().isBefore(verified.stepExpiresAt) -> "reason=STEP_EXPIRED" to true
            !CODE.matches(code) -> "reason=FORMAT" to false
            else -> null
        }
        if (early != null) {
            transactions.execute({ false }) { accounts.find(id)?.value?.let { audit(now(), it, TOTP, REJECTED, context, early.first) } }
            metrics.login(TOTP_STEP, REJECTED)
            return TotpStepResult.Rejected(endSession = early.second)
        }
        val label = StepLabel(REJECTED)
        return observed(TOTP_STEP, label) { retryLaterOnConflict {
            transactions.execute({ false }) {
                val now = now()
                val current = accounts.lock(id) ?: return@execute TotpStepResult.Rejected(endSession = true)
                val account = current.value
                val credential = account.totp
                if (account.status != AccountStatus.ACTIVE || account.securityVersion != verified.securityVersion || credential == null) {
                    audit(now, account, TOTP, REJECTED, context, STALE, label)
                    return@execute TotpStepResult.Rejected(endSession = true)
                }
                val secret = cipher.decrypt(SealedSecret(credential.keyVersion, credential.ciphertext), CipherContext(CipherContext.STORED_TOTP, listOf(id.value)))
                val step = try {
                    verifier.matchingStep(secret, code, clock.now(), settings.totpWindowSteps)
                } finally {
                    secret.fill(0)
                }
                val accepted = step?.let { account.acceptTotpStep(it, now) } as? AccountChange.Applied
                if (accepted != null) {
                    val next = accepted.account.recordAuthenticationSuccess(now).startNewSession(now)
                    accounts.update(current, next)
                    audit(now, account, SUCCEEDED, SUCCESS, context, null, label)
                    TotpStepResult.Authenticated(id.value, next.sessionGeneration, next.securityVersion, now, settings.authenticatedSessionIdle)
                } else {
                    val failure = account.recordAuthenticationFailure(settings.loginAttemptPolicy, now)
                    if (failure !is AuthenticationFailure.NotCounted) accounts.update(current, failure.account)
                    val locked = failure is AuthenticationFailure.Locked
                    audit(now, account, TOTP, if (locked) LOCKED else FAILED, context, "failures=${failure.account.failedAttempts}", label)
                    TotpStepResult.Rejected(endSession = locked)
                }
            }
        } }
    }

    override fun logout(actorId: String, context: AdminRequestContext) {
        val id = runCatching { AdminActorId(actorId) }.getOrNull() ?: return
        transactions.execute({ false }) {
            val account = accounts.find(id)?.value ?: return@execute
            audit(now(), account, LOGOUT, SUCCESS, context, null)
        }
    }

    @Suppress("LongParameterList")
    private fun audit(now: Instant, account: AdminAccount, action: String, result: String, context: AdminRequestContext, detail: String?, label: StepLabel? = null) {
        label?.result = result
        recorder.record(
            AuditRecord(
                occurredAt = now, actorKind = "ADMIN", actorId = account.id.value, actorDisplayName = account.displayName.value, action = action, resultCode = result,
                correlationId = context.correlationId, targetActorId = account.id.value, remoteIp = context.remoteIp, detail = detail,
            ),
        )
    }

    /** S8 지표: 커밋된 결과(감사와 같은 분류)만 센다 — 409·500은 `RETRY_LATER`·`ERROR`. LOCKED면 잠금 지표도. */
    private inline fun <T> observed(step: String, label: StepLabel, block: () -> T): T {
        val value = try {
            block()
        } catch (e: AdminRetryLaterException) {
            metrics.login(step, RETRY_LATER)
            throw e
        } catch (e: RuntimeException) {
            metrics.login(step, ERROR)
            throw e
        }
        metrics.login(step, label.result)
        if (label.result == LOCKED) metrics.lockout(step)
        return value
    }

    /** 한 호출의 지표 결과(TX 안에서 감사와 함께 정해진다). */
    private class StepLabel(var result: String)

    private fun anonymous(now: Instant, loginName: String, context: AdminRequestContext) = recorder.record(
        AuditRecord(
            occurredAt = now, actorKind = "ANONYMOUS", actorId = null, actorDisplayName = null, action = PASSWORD, resultCode = FAILED,
            correlationId = context.correlationId, remoteIp = context.remoteIp, loginNameHmac = digester.digest(loginName), detail = "step=PASSWORD",
        ),
    )

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    private companion object {
        const val PASSWORD = "LOGIN_PASSWORD"
        const val TOTP = "LOGIN_TOTP"
        const val SUCCEEDED = "LOGIN_SUCCEEDED"
        const val LOGOUT = "LOGOUT"
        const val SUCCESS = "SUCCESS"
        const val FAILED = "FAILED"
        const val LOCKED = "LOCKED"
        const val NOT_COUNTED = "NOT_COUNTED"
        const val REJECTED = "REJECTED"
        const val STALE = "reason=STALE"
        const val PASSWORD_STEP = "PASSWORD"
        const val TOTP_STEP = "TOTP"
        const val UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT"
        const val RETRY_LATER = "RETRY_LATER"
        const val ERROR = "ERROR"
        val CODE = Regex("^[0-9]{6}$")
        val EMPTY = CharArray(0)
    }
}
