package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AdminOwnedCommandExecutor
import com.paycore.admin.account.application.provided.AuthenticationFailureRecorder
import com.paycore.admin.account.application.provided.AuthenticationStep
import com.paycore.admin.account.application.provided.StepUpResult
import com.paycore.admin.account.application.provided.UnmaskRequest
import com.paycore.admin.account.application.provided.UnmaskResult
import com.paycore.admin.account.application.provided.UnmaskUseCase
import com.paycore.admin.account.application.required.AdminClock
import com.paycore.admin.account.application.required.DemoQuickLoginSettings
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.temporal.ChronoUnit

/** [UnmaskUseCase] 틀(AC10). 대상 데이터 공급 Port는 아직 없다(후속 작업이 추가) — 통과해도 [UnmaskResult.NoDataSource]. */
@Service
class UnmaskService(
    transactionManager: PlatformTransactionManager,
    private val executor: AdminOwnedCommandExecutor,
    private val failures: AuthenticationFailureRecorder,
    private val recorder: AdminAuditRecorder,
    private val clock: AdminClock,
    private val demoQuickLogin: DemoQuickLoginSettings,
) : UnmaskUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    override fun request(command: UnmaskRequest): UnmaskResult {
        if (command.reason.isBlank()) return UnmaskResult.Invalid("REASON_REQUIRED")
        if (command.reason.length > REASON_MAX || command.reason.any { it.isISOControl() }) return UnmaskResult.Invalid("REASON_INVALID")
        if (!TARGET.matches(command.targetRef)) return UnmaskResult.Invalid("TARGET_INVALID")
        // ADMIN-DEMO-LOGIN-001 결정 ③: 데모 간편 로그인 세션 + 켜짐이면 TOTP 입력이 없어도 된다(검증 생략은 executor.verifyStepUp — 같은 조건). 그 밖은 그대로.
        if (command.totpCode.isBlank() && !demoQuickLogin.waivesStepUp(command.context)) return UnmaskResult.Invalid("TOTP_REQUIRED")
        val result = retryLaterOnConflict {
            transactions.execute({ false }) { status ->
                val actor = executor.lockParticipants(listOf(command.actorId))[command.actorId]
                if (actor == null || !actor.holds(UNMASK_ROLE)) {
                    // 권한 없는 시도도 감사 1행(D-65와 같은 기준 — 상태 변경이 없으므로 이 TX로 커밋). 사유 자유 문구는 권한 없는 입력이라 남기지 않는다.
                    recorder.record(
                        AuditRecord(
                            occurredAt = clock.now().truncatedTo(ChronoUnit.MILLIS), actorKind = if (actor != null) "ADMIN" else "ANONYMOUS", actorId = actor?.actorId,
                            actorDisplayName = actor?.displayName, action = ACTION, resultCode = "DENIED", correlationId = command.context.correlationId,
                            remoteIp = command.context.remoteIp, detail = "target=${command.targetRef}",
                        ),
                    )
                    return@execute UnmaskResult.Denied
                }
                if (actor.sessionGeneration != command.sessionGeneration) return@execute UnmaskResult.SessionReplaced
                if (executor.verifyStepUp(command.actorId, command.totpCode, command.context) == StepUpResult.FAILED) {
                    status.setRollbackOnly()
                    return@execute UnmaskResult.StepUpFailed
                }
                recorder.record(
                    AuditRecord(
                        occurredAt = clock.now().truncatedTo(ChronoUnit.MILLIS), actorKind = "ADMIN", actorId = actor.actorId, actorDisplayName = actor.displayName,
                        action = ACTION, resultCode = "SUCCESS", correlationId = command.context.correlationId, remoteIp = command.context.remoteIp,
                        detail = "target=${command.targetRef}", reason = command.reason,
                    ),
                )
                UnmaskResult.NoDataSource
            }
        }
        if (result == UnmaskResult.StepUpFailed) failures.recordFailure(command.actorId, AuthenticationStep.STEP_UP_TOTP, command.context)
        return result
    }

    private companion object {
        const val UNMASK_ROLE = "UNMASK"
        const val ACTION = "UNMASK_REQUESTED"
        const val REASON_MAX = 500
        val TARGET = Regex("^[A-Za-z0-9_:-]{1,64}$")
    }
}
