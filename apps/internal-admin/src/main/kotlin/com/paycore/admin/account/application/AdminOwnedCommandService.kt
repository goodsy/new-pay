package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AdminOwnedCommandExecutor
import com.paycore.admin.account.application.provided.OwnedCommandEvidence
import com.paycore.admin.account.application.provided.OwnedCommandOutcome
import com.paycore.admin.account.application.provided.ParticipantView
import com.paycore.admin.account.application.provided.StepUpResult
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminClock
import com.paycore.admin.account.application.required.AdminRoleEventStore
import com.paycore.admin.account.application.required.AdminSecuritySettings
import com.paycore.admin.account.application.required.CipherContext
import com.paycore.admin.account.application.required.EnrollmentCodeGenerator
import com.paycore.admin.account.application.required.EnrollmentCodeStore
import com.paycore.admin.account.application.required.RecoveryEpisodeStore
import com.paycore.admin.account.application.required.SealedSecret
import com.paycore.admin.account.application.required.TotpSecretCipher
import com.paycore.admin.account.application.required.TotpVerifier
import com.paycore.admin.account.application.required.Versioned
import com.paycore.admin.account.domain.AccountChange
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.EnrollmentCode
import com.paycore.admin.account.domain.EnrollmentPurpose
import com.paycore.admin.account.domain.RoleSuspensions
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [AdminOwnedCommandExecutor] — approval orchestrator의 TX 안에서만(MANDATORY). 종류별 실행(§4.7.6·§4.7.7):
 * `ROLE_GRANT` 역할 추가·GRANT 이벤트(행위자 = 승인자), `ACCOUNT_UNLOCK` LOCKED → ACTIVE·계수 0, `ACCOUNT_REACTIVATE` SUSPENDED → 정지 직전 상태(잠금 시각 보존),
 * `PASSWORD_RESET`·`TOTP_REENROLL` 대기 전이·역할 SUSPEND·기존 코드 폐기·새 코드(근거 = approvalId), `ROLE_REACTIVATE` 원인 재설정으로 정지된 역할만 REACTIVATE.
 * 모두 security_version·세대 +1(요청마다 세션 검사가 대상의 기존 세션을 끝낸다). 감사는 orchestrator가 같은 TX에서 쓴다(실행자 = 승인자).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class AdminOwnedCommandService(
    private val accounts: AdminAccountRepository,
    private val roleEvents: AdminRoleEventStore,
    private val codes: EnrollmentCodeStore,
    private val codeGenerator: EnrollmentCodeGenerator,
    private val episodes: RecoveryEpisodeStore,
    private val cipher: TotpSecretCipher,
    private val verifier: TotpVerifier,
    private val clock: AdminClock,
    private val settings: AdminSecuritySettings,
) : AdminOwnedCommandExecutor {

    override fun lockOpenRecoveryEpisode(): Boolean = retryLaterOnConflict { episodes.lockOpen() != null }

    override fun lockParticipants(actorIds: Collection<String>): Map<String, ParticipantView> = retryLaterOnConflict {
        val ids = actorIds.mapNotNull { runCatching { AdminActorId(it) }.getOrNull() }
        accounts.lockAll(ids).map { (id, row) -> id.value to view(row.value) }.toMap()
    }

    override fun snapshot(actorId: String): ParticipantView? = retryLaterOnConflict {
        runCatching { AdminActorId(actorId) }.getOrNull()?.let { accounts.find(it)?.value }?.let(::view)
    }

    override fun consumeStepUpTotp(actorId: String, code: String): StepUpResult = retryLaterOnConflict {
        val current = current(actorId) ?: return@retryLaterOnConflict StepUpResult.FAILED
        val account = current.value
        val credential = account.totp
        if (account.status != AccountStatus.ACTIVE || credential == null || !CODE.matches(code)) return@retryLaterOnConflict StepUpResult.FAILED
        val secret = cipher.decrypt(SealedSecret(credential.keyVersion, credential.ciphertext), CipherContext(CipherContext.STORED_TOTP, listOf(account.id.value)))
        val step = try {
            verifier.matchingStep(secret, code, clock.now(), settings.totpWindowSteps)
        } finally {
            secret.fill(0)
        }
        val accepted = step?.let { account.acceptTotpStep(it, now()) } as? AccountChange.Applied ?: return@retryLaterOnConflict StepUpResult.FAILED
        replace(current, accepted.account)
        StepUpResult.ACCEPTED
    }

    override fun rolesSuspendedBy(targetActorId: String, causeApprovalId: String): Set<String> = retryLaterOnConflict {
        val id = runCatching { AdminActorId(targetActorId) }.getOrNull() ?: return@retryLaterOnConflict emptySet()
        // 역할마다 마지막 이벤트가 이 원인의 SUSPEND이면 아직 그 원인으로 정지된 상태(그 뒤 REVOKE·REACTIVATE가 있으면 아님) — 원인 후보 조회와 같은 domain 판정(S8).
        RoleSuspensions.byCause(roleEvents.history(id))[causeApprovalId].orEmpty().map { it.name }.toSet()
    }

    override fun execute(evidence: OwnedCommandEvidence): OwnedCommandOutcome = retryLaterOnConflict {
        val ids = listOf(evidence.requesterActorId, evidence.approverActorId, evidence.targetActorId)
        if (ids.toSet().size != ids.size) return@retryLaterOnConflict OwnedCommandOutcome.Rejected("PARTICIPANTS_NOT_DISTINCT")
        val current = current(evidence.targetActorId) ?: return@retryLaterOnConflict OwnedCommandOutcome.Rejected("TARGET_NOT_FOUND")
        val target = current.value
        if (target.securityVersion != evidence.targetSecurityVersion) return@retryLaterOnConflict OwnedCommandOutcome.Rejected("TARGET_VERSION_CHANGED")
        // D-ADMIN1-014·015: 현재 자격 발급 근거의 요청자·승인자(코드 수령자)는 이 계정의 권한 상승 요청자·승인자가 될 수 없다(소유 재검사 — 요청·승인 시점 판정과 같은 규칙).
        if (evidence.kind in ESCALATIONS && ids.any { it != evidence.targetActorId && target.credentialBasis.isIssuer(AdminActorId(it)) }) {
            return@retryLaterOnConflict OwnedCommandOutcome.Rejected(CREDENTIAL_ISSUER)
        }
        val now = now()
        val approver = AdminActorId(evidence.approverActorId)
        var code: String? = null
        val change = when (evidence.kind) {
            "ROLE_GRANT" -> {
                val role = evidence.grantedRole?.let { r -> AdminRole.entries.firstOrNull { it.name == r } }
                    ?: return@retryLaterOnConflict OwnedCommandOutcome.Rejected("INVALID_ROLE")
                target.grantRole(role, com.paycore.admin.account.domain.RoleEventActor.Admin(approver), evidence.approvalId, now)
            }
            "ACCOUNT_UNLOCK" -> target.unlock(now)
            "ACCOUNT_REACTIVATE" -> target.reactivate(now)
            "PASSWORD_RESET", "TOTP_REENROLL" -> target.beginCredentialReset(EnrollmentPurpose.valueOf(evidence.kind), evidence.approvalId, AdminActorId(evidence.requesterActorId), approver, now)
            "ROLE_REACTIVATE" -> {
                val cause = evidence.causeApprovalId ?: return@retryLaterOnConflict OwnedCommandOutcome.Rejected("CAUSE_REQUIRED")
                val suspended = rolesSuspendedBy(evidence.targetActorId, cause).map(AdminRole::valueOf).toSet()
                target.reactivateRoles(cause, suspended, approver, evidence.approvalId, now)
            }
            else -> return@retryLaterOnConflict OwnedCommandOutcome.Rejected("UNKNOWN_KIND")
        }
        when (change) {
            is AccountChange.Rejected -> OwnedCommandOutcome.Rejected(change.reason.name)
            is AccountChange.Applied -> {
                roleEvents.append(change.events)
                if (evidence.kind == "PASSWORD_RESET" || evidence.kind == "TOTP_REENROLL") {
                    // 대상당 활성 코드 1개: 기존 미사용 코드 폐기 후 새 코드(근거 = 이 approvalId). 원문은 결과로 1회만.
                    codes.revokeUnused(target.id, now)
                    val issued = codeGenerator.newCode()
                    codes.insert(EnrollmentCode.issue(issued.hash, target.id, requireNotNull(change.account.pending), now, now.plus(settings.enrollmentCodeValidity)))
                    code = issued.displayCode
                }
                replace(current, change.account)
                OwnedCommandOutcome.Executed(code)
            }
        }
    }

    /** 이 TX에서 이미 잠근 행의 현재값(같은 TX의 재잠금은 기다리지 않는다 — 잠금 순서는 `lockParticipants`가 먼저 정한다). */
    private fun current(actorId: String): Versioned<AdminAccount>? =
        runCatching { AdminActorId(actorId) }.getOrNull()?.let { accounts.lock(it) }

    private fun replace(current: Versioned<AdminAccount>, next: AdminAccount) {
        accounts.update(current, next)
    }

    private fun view(a: AdminAccount) = ParticipantView(
        a.id.value, a.loginName.value, a.displayName.value, a.status.name, a.activeRoles.map { it.name }.toSet(), a.suspendedRoles.map { it.name }.toSet(),
        a.sessionGeneration, a.securityVersion, a.credentialBasis.issuers.map { it.value }.toSet(),
    )

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    private companion object {
        val CODE = Regex("^[0-9]{6}$")
        val ESCALATIONS = setOf("ROLE_GRANT", "ROLE_REACTIVATE")
        const val CREDENTIAL_ISSUER = "CREDENTIAL_ISSUER"
    }
}
