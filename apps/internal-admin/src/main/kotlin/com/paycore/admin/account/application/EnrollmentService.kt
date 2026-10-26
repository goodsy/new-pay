package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.EnrollmentDraft
import com.paycore.admin.account.application.provided.EnrollmentFailure
import com.paycore.admin.account.application.provided.EnrollmentResult
import com.paycore.admin.account.application.provided.EnrollmentUseCase
import com.paycore.admin.account.application.provided.SealedValue
import com.paycore.admin.account.application.required.AdminAccountMetrics
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminClock
import com.paycore.admin.account.application.required.AdminIdGenerator
import com.paycore.admin.account.application.required.AdminSecuritySettings
import com.paycore.admin.account.application.required.CipherContext
import com.paycore.admin.account.application.required.EnrollmentCodeStore
import com.paycore.admin.account.application.required.AdminRoleEventStore
import com.paycore.admin.account.application.required.RecoveryEpisode
import com.paycore.admin.account.application.required.RecoveryEpisodeStore
import com.paycore.admin.account.application.required.PasswordHasher
import com.paycore.admin.account.application.required.SealedSecret
import com.paycore.admin.account.application.required.SecretUnsealFailure
import com.paycore.admin.account.application.required.TotpSecretCipher
import com.paycore.admin.account.application.required.TotpSecretGenerator
import com.paycore.admin.account.application.required.TotpVerifier
import com.paycore.admin.account.domain.AccountChange
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.EnrollmentCode
import com.paycore.admin.account.domain.RecoveryBasis
import com.paycore.admin.account.domain.RoleEventType
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.Base32
import com.paycore.admin.account.domain.EnrollmentCodeHash
import com.paycore.admin.account.domain.EnrollmentPurpose
import com.paycore.admin.account.domain.NewTotp
import com.paycore.admin.account.domain.PasswordHash
import com.paycore.admin.account.domain.PendingEnrollment
import com.paycore.admin.account.domain.TotpCredential
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [EnrollmentUseCase](§4.3.6). 코드 확인·진행 상태는 계정 행을 바꾸지 않고, 완료 TX만 계정을 바꾼다.
 * - 대기 값(새 비밀번호 해시·새 TOTP 시크릿)은 `TotpSecretCipher`로 암호화해 draft에만 둔다. AAD 부분 = (진행 ID, 대상 actor ID, 목적, 용도 태그).
 * - 완료 TX(잠금 순서 §4.13): 대상 계정 `FOR UPDATE` → 코드 행 재확인(대상·목적·근거) → domain 상태·대기 사유 검사(H-B) → 코드 원자 소비(영향 0이면 전체 rollback)
 *   → 계정 갱신 → 감사 → 커밋. TOTP 확인 코드 검증과 저장용 암호화는 TX 밖(외부 호출이 아닌 순수 계산 — 잠금 시간을 늘리지 않는다).
 * - 실패는 전체 rollback(코드 미소비). 코드·단계 실패는 익명 시도라 감사 대신 지표 대상이다(§4.3.6 "지표만" — 지표는 S8). 단 완료 TX의 적용 불가(대상 확정)는
 *   rollback 뒤 별도 TX로 `ENROLLMENT_COMPLETED:REJECTED`(`reason=NOT_APPLICABLE`, 행위자 ANONYMOUS)를 남긴다(D-67 갱신).
 * - (S6) 활성 수 판정 직렬화(§4.9.2·§4.13): 0단계 비잠금 선판정으로 "복구 회차 코드이거나 완료 시 SECURITY_ADMIN·APPROVER가 활성화되는 계정"이면 열린 회차 행을
 *   먼저 잠그고, 계정 잠금 아래 같은 판정·열린 회차를 다시 확인한다(다르면 전체 rollback → 409). 복구 코드는 기록 역할마다 [회차 열림·만료 전·여전히 이 회차 원인 정지·
 *   그 역할 활성 수 0]일 때만 `SYSTEM_RECOVERY` 재활성, 그다음 회차의 남은 코드가 0이면 회차를 닫는다(회차 잠금 아래).
 */
@Service
class EnrollmentService(
    transactionManager: PlatformTransactionManager,
    private val accounts: AdminAccountRepository,
    private val codes: EnrollmentCodeStore,
    private val passwordHasher: PasswordHasher,
    private val cipher: TotpSecretCipher,
    private val totpSecrets: TotpSecretGenerator,
    private val totpVerifier: TotpVerifier,
    private val ids: AdminIdGenerator,
    private val clock: AdminClock,
    private val settings: AdminSecuritySettings,
    private val recorder: AdminAuditRecorder,
    private val episodes: RecoveryEpisodeStore,
    private val roleEvents: AdminRoleEventStore,
    private val metrics: AdminAccountMetrics,
) : EnrollmentUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    override fun begin(rawCode: String): EnrollmentResult = observed("BEGIN") { beginStep(rawCode) }

    override fun providePassword(draft: EnrollmentDraft, password: CharArray): EnrollmentResult = observed("PASSWORD") { passwordStep(draft, password) }

    override fun startTotp(draft: EnrollmentDraft): EnrollmentResult = observed("TOTP") { totpStep(draft) }

    override fun complete(draft: EnrollmentDraft, totpCode: String?, context: AdminRequestContext): EnrollmentResult =
        observed("COMPLETE") { completeStep(draft, totpCode, context) }

    /** S8 지표(`paycore.admin.enrollment{operation,result}`): 코드 무효 등 익명 시도는 감사 대신 이 지표만(D-67). */
    private inline fun observed(operation: String, block: () -> EnrollmentResult): EnrollmentResult {
        val result = try {
            block()
        } catch (e: com.paycore.admin.account.application.provided.AdminRetryLaterException) {
            metrics.enrollment(operation, "RETRY_LATER")
            throw e
        } catch (e: RuntimeException) {
            metrics.enrollment(operation, "ERROR")
            throw e
        }
        metrics.enrollment(operation, if (result is EnrollmentResult.Failed) result.failure.name else "OK")
        return result
    }

    private fun beginStep(rawCode: String): EnrollmentResult {
        val normalized = EnrollmentCodeHash.normalize(rawCode) ?: return failed(EnrollmentFailure.CODE_INVALID)
        val hash = EnrollmentCodeHash.of(normalized)
        return transactions.execute({ false }) {
            val now = now()
            val code = codes.find(hash)
            when {
                code == null || !code.isUsable(now) -> failed(EnrollmentFailure.CODE_INVALID)
                else -> EnrollmentResult.InProgress(
                    EnrollmentDraft(
                        progressId = ids.newProgressId(), codeHash = hash.bytes, targetActorId = code.target.value, purpose = code.purpose.name,
                        basis = code.basis, expiresAt = now.plus(settings.enrollmentStepValidity),
                    ),
                )
            }
        }
    }

    private fun passwordStep(draft: EnrollmentDraft, password: CharArray): EnrollmentResult {
        val purpose = usablePurpose(draft) ?: return failed(EnrollmentFailure.STEP_INVALID)
        if (!purpose.setsPassword) return failed(EnrollmentFailure.STEP_INVALID)
        // 최소 길이는 Unicode code point 수(서로게이트 쌍 = 1자), 최대는 UTF-8 바이트 수(BCrypt 72바이트 상한) — §20.8 nit.
        if (Character.codePointCount(password, 0, password.size) < settings.passwordMinLength) return failed(EnrollmentFailure.PASSWORD_TOO_SHORT)
        if (utf8Length(password) > settings.passwordMaxBytes) return failed(EnrollmentFailure.PASSWORD_TOO_LONG)
        val hash = passwordHasher.hash(password)
        val sealed = cipher.encrypt(hash.value.toByteArray(Charsets.UTF_8), pendingContext(draft, PASSWORD_HASH_TAG))
        return EnrollmentResult.InProgress(draft.with(pendingPasswordHash = SealedValue(sealed.keyVersion, sealed.bytes)))
    }

    private fun totpStep(draft: EnrollmentDraft): EnrollmentResult {
        val purpose = usablePurpose(draft) ?: return failed(EnrollmentFailure.STEP_INVALID)
        if (!purpose.setsTotp) return failed(EnrollmentFailure.STEP_INVALID)
        val loginName = transactions.execute({ false }) { accounts.find(AdminActorId(draft.targetActorId))?.value?.loginName?.value }
            ?: return failed(EnrollmentFailure.NOT_APPLICABLE)
        val secret = totpSecrets.newSecret()
        try {
            val sealed = cipher.encrypt(secret, pendingContext(draft, TOTP_SECRET_TAG))
            return EnrollmentResult.TotpProvisioning(draft.with(pendingTotpSecret = SealedValue(sealed.keyVersion, sealed.bytes)), otpauthUri(loginName, secret))
        } finally {
            secret.fill(0)
        }
    }

    private fun completeStep(draft: EnrollmentDraft, totpCode: String?, context: AdminRequestContext): EnrollmentResult {
        val purpose = usablePurpose(draft) ?: return failed(EnrollmentFailure.STEP_INVALID)
        if ((draft.pendingPasswordHash != null) != purpose.setsPassword) return failed(EnrollmentFailure.STEP_INVALID)
        if ((draft.pendingTotpSecret != null) != purpose.setsTotp || (totpCode != null) != purpose.setsTotp) return failed(EnrollmentFailure.STEP_INVALID)
        val target = AdminActorId(draft.targetActorId)
        val pending = PendingEnrollment(purpose, draft.basis)
        val prepared = try {
            prepare(draft, target, totpCode)
        } catch (_: SecretUnsealFailure) {
            null
        } ?: return failed(EnrollmentFailure.STEP_INVALID)
        val hash = EnrollmentCodeHash(draft.codeHash)
        val result = try {
            retryLaterOnConflict { completeInTransaction(hash, target, pending, prepared, context) }
        } catch (e: RecoverySerializationChanged) {
            throw com.paycore.admin.account.application.provided.AdminRetryLaterException(e)
        }
        return afterComplete(result, target, context)
    }

    private fun completeInTransaction(hash: EnrollmentCodeHash, target: AdminActorId, pending: PendingEnrollment, prepared: Prepared, context: AdminRequestContext): EnrollmentResult =
        run {
            transactions.execute({ false }) { status ->
            val now = now()
            // 0단계(비잠금 선판정): 회차 코드이거나 SA·APPROVER가 활성화되는 계정이면 열린 회차 행을 먼저 잠근다(§4.13 회차 → 계정 → 코드).
            val activatesBefore = activatesPrivileged(codes.find(hash), accounts.find(target)?.value?.activeRoles)
            val episode = if (activatesBefore) episodes.lockOpenEpisode() else null
            val current = accounts.lock(target)
            val code = codes.find(hash)
            // 잠금 아래 재확인: 선판정·열린 회차가 달라졌으면(그 사이 회차가 열림 등) 회차 잠금 없이 활성화하지 않도록 전체 rollback → 409.
            if (activatesPrivileged(code, current?.value?.activeRoles) != activatesBefore || (activatesBefore && episodes.findOpen()?.episodeNo != episode?.episodeNo)) {
                throw RecoverySerializationChanged()
            }
            // 사용된 코드는 대상 확정 전의 코드 무효(재사용 — 지표만). 폐기(정지·재발급이 함께 대기 사유를 바꾼다)는 아래 domain 검사가 적용 불가로 판정하고,
            // 만료는 원자 소비 UPDATE의 `expires_at > now`가 TX 안 서버 시계로 판정한다.
            if (current == null || code == null || code.target != target || code.pending != pending || code.usedAt != null) {
                status.setRollbackOnly()
                return@execute failed(EnrollmentFailure.CODE_INVALID)
            }
            when (val change = current.value.completeEnrollment(pending, prepared.password, prepared.totp, now)) {
                is AccountChange.Rejected -> {
                    status.setRollbackOnly()
                    failed(EnrollmentFailure.NOT_APPLICABLE)
                }
                is AccountChange.Applied -> {
                    if (!codes.consume(hash, now)) {
                        status.setRollbackOnly()
                        return@execute failed(EnrollmentFailure.CODE_INVALID)
                    }
                    val restored = restoreRecoveredRoles(code, change.account, episode, now)
                    accounts.update(current, restored?.account ?: change.account)
                    restored?.let { roleEvents.append(it.events) }
                    closeEpisodeIfDone(code, episode, now, context)
                    recorder.record(
                        AuditRecord(
                            occurredAt = now, actorKind = "ADMIN", actorId = target.value, actorDisplayName = change.account.displayName.value,
                            action = ENROLLMENT_COMPLETED, resultCode = AccountAuditWriter.SUCCESS, correlationId = context.correlationId,
                            targetActorId = target.value, remoteIp = context.remoteIp, detail = "purpose=${pending.purpose.name}",
                        ),
                    )
                    EnrollmentResult.Completed(target.value)
                }
            }
            }
        }

    private fun afterComplete(result: EnrollmentResult, target: AdminActorId, context: AdminRequestContext): EnrollmentResult {
        // S3 리뷰 m-1: 대상이 확정된 적용 불가(유효했던 코드·진행 상태로, 그 사이 정지·근거 교체된 계정의 완료 시도)는 주 TX가 rollback된 뒤 별도 TX로 감사한다.
        // 코드 무효(없음·만료·사용·폐기)는 대상이 확정되지 않은 익명 시도라 지표만(D-67).
        if (result == failed(EnrollmentFailure.NOT_APPLICABLE)) {
            transactions.execute({ false }) {
                recorder.record(
                    AuditRecord(
                        occurredAt = now(), actorKind = "ANONYMOUS", actorId = null, actorDisplayName = null, action = ENROLLMENT_COMPLETED,
                        resultCode = AccountAuditWriter.REJECTED, correlationId = context.correlationId, targetActorId = target.value,
                        remoteIp = context.remoteIp, detail = AccountAuditWriter.reason(EnrollmentFailure.NOT_APPLICABLE.name),
                    ),
                )
            }
        }
        return result
    }

    /** 회차 코드(복구 A·B)이거나 완료 시 SECURITY_ADMIN·APPROVER가 활성화되는 계정(현재 활성 역할 또는 복구 기록 역할)인가. */
    private fun activatesPrivileged(code: EnrollmentCode?, activeRoles: Set<AdminRole>?): Boolean =
        code?.recoveryEpisode != null || code?.recoveryRoles?.isNotEmpty() == true || activeRoles.orEmpty().any { it in AdminAccount.RECOVERY_ROLES }

    /**
     * 복구 코드 완료의 자동 재활성(§4.9.2, D-ADMIN1-012 — Coordinator fail-closed 판정): 코드 행에 기록된 역할마다 회차가 열려 있고(잠금 행) 만료 전이며, 그 역할의 최신
     * 이벤트가 이 회차 원인 SUSPEND(그 사이 회수·재활성 없음)이고, 그 역할의 활성 계정 수가 여전히 0일 때만. 못 맞춘 역할은 정지 유지(일반 `ROLE_REACTIVATE` 2인 승인).
     */
    private fun restoreRecoveredRoles(code: EnrollmentCode, account: AdminAccount, episode: RecoveryEpisode?, now: Instant): AccountChange.Applied? {
        if (code.purpose != EnrollmentPurpose.RECOVERY) return null
        val episodeNo = code.recoveryEpisode ?: return null
        if (episode == null || episode.episodeNo != episodeNo || !episode.isOpenAt(now)) return null
        val basis = RecoveryBasis.of(episodeNo)
        val history = roleEvents.history(account.id).groupBy { it.role }
        val restorable = code.recoveryRoles.orEmpty().filter { role ->
            history[role]?.lastOrNull()?.let { it.type == RoleEventType.SUSPEND && it.cause == basis } == true && accounts.countActiveHolders(role) == 0
        }.toSet()
        if (restorable.isEmpty()) return null
        return account.reactivateRecoveredRoles(restorable, basis, now) as? AccountChange.Applied
    }

    /** 회차 종료 판정(회차 행 잠금 아래): 이 회차의 쓸 수 있는 코드가 0이면 닫는다. */
    private fun closeEpisodeIfDone(code: EnrollmentCode, episode: RecoveryEpisode?, now: Instant, context: AdminRequestContext) {
        val episodeNo = code.recoveryEpisode ?: return
        if (episode == null || episode.episodeNo != episodeNo) return
        if (codes.countUsableForEpisode(episodeNo, now) > 0) return
        episodes.close(episodeNo, now)
        recorder.record(
            AuditRecord(
                occurredAt = now, actorKind = "SYSTEM_RECOVERY", actorId = null, actorDisplayName = null, action = "RECOVERY_EPISODE_CLOSED", resultCode = AccountAuditWriter.SUCCESS,
                correlationId = context.correlationId, detail = "episode=$episodeNo",
            ),
        )
    }

    /** 0단계 선판정과 잠금 아래 판정이 다름(재시도 가능 — 409). */
    private class RecoverySerializationChanged : RuntimeException(null, null, false, false)

    /** 대기 값 복호 → (TOTP면) 확인 코드 검증 → 저장 문맥으로 재암호화. TOTP 확인 실패는 null(코드 미소비). */
    private fun prepare(draft: EnrollmentDraft, target: AdminActorId, totpCode: String?): Prepared? {
        val password = draft.pendingPasswordHash?.let {
            PasswordHash(String(cipher.decrypt(SealedSecret(it.keyVersion, it.bytes), pendingContext(draft, PASSWORD_HASH_TAG)), Charsets.UTF_8))
        }
        val totp = draft.pendingTotpSecret?.let { sealedSecret ->
            val secret = cipher.decrypt(SealedSecret(sealedSecret.keyVersion, sealedSecret.bytes), pendingContext(draft, TOTP_SECRET_TAG))
            try {
                val step = totpVerifier.matchingStep(secret, requireNotNull(totpCode), clock.now(), settings.totpWindowSteps) ?: return null
                val stored = cipher.encrypt(secret, CipherContext(CipherContext.STORED_TOTP, listOf(target.value)))
                NewTotp(TotpCredential(stored.bytes, stored.keyVersion), step)
            } finally {
                secret.fill(0)
            }
        }
        return Prepared(password, totp)
    }

    private class Prepared(val password: PasswordHash?, val totp: NewTotp?)

    /** 진행 상태가 만료 전이고 목적이 알려진 값이면 그 목적. */
    private fun usablePurpose(draft: EnrollmentDraft): EnrollmentPurpose? {
        if (!clock.now().isBefore(draft.expiresAt)) return null
        return EnrollmentPurpose.entries.firstOrNull { it.name == draft.purpose }
    }

    private fun pendingContext(draft: EnrollmentDraft, use: String) =
        CipherContext(CipherContext.ENROLLMENT_PENDING, listOf(draft.progressId, draft.targetActorId, draft.purpose, use))

    /** `otpauth://totp/<issuer>:<login>?secret=…&issuer=…&algorithm=SHA1&digits=6&period=30`(Key Uri Format). 응답 1회만. */
    private fun otpauthUri(loginName: String, secret: ByteArray): String {
        val issuer = percentEncode(settings.totpIssuer)
        return "otpauth://totp/$issuer:${percentEncode(loginName)}?secret=${Base32.encode(secret)}&issuer=$issuer&algorithm=SHA1&digits=6&period=30"
    }

    private fun failed(failure: EnrollmentFailure) = EnrollmentResult.Failed(failure)

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    internal companion object {
        const val PASSWORD_HASH_TAG = "password-hash"
        const val TOTP_SECRET_TAG = "totp-secret"
        const val ENROLLMENT_COMPLETED = "ENROLLMENT_COMPLETED"

        /** RFC 3986 unreserved 외 문자를 UTF-8 `%XX`로(`java.net`은 application 허용 목록 밖). */
        fun percentEncode(value: String): String = buildString {
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF)
                if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") append(c.toChar()) else append("%%%02X".format(c))
            }
        }

        fun utf8Length(chars: CharArray): Int {
            var bytes = 0
            var i = 0
            while (i < chars.size) {
                val c = chars[i]
                bytes += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < chars.size && Character.isLowSurrogate(chars[i + 1]) -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return bytes
        }
    }
}
