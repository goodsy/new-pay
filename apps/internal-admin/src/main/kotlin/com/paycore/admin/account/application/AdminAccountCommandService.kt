package com.paycore.admin.account.application

import com.paycore.admin.account.application.AccountAuditWriter.Companion.DENIED
import com.paycore.admin.account.application.AccountAuditWriter.Companion.REJECTED
import com.paycore.admin.account.application.AccountAuditWriter.Companion.SUCCESS
import com.paycore.admin.account.application.AccountAuditWriter.Companion.reason
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.CreateAdminAccount
import com.paycore.admin.account.application.provided.RevokeAdminRole
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminClock
import com.paycore.admin.account.application.required.AdminIdGenerator
import com.paycore.admin.account.application.required.AdminRoleEventStore
import com.paycore.admin.account.application.required.AdminSecuritySettings
import com.paycore.admin.account.application.required.EnrollmentCodeGenerator
import com.paycore.admin.account.application.required.EnrollmentCodeStore
import com.paycore.admin.account.application.required.PersistenceFailure
import com.paycore.admin.account.application.required.Versioned
import com.paycore.admin.account.domain.AccountChange
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.CredentialBasis
import com.paycore.admin.account.domain.AdminAction
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminPermission
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.DisplayName
import com.paycore.admin.account.domain.EnrollmentCode
import com.paycore.admin.account.domain.LoginName
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [AdminAccountCommandUseCase] — 명령마다 로컬 TX 하나: 요청자·대상 행을 actor ID 오름차순으로 잠금(§4.13) → 요청자 권한(계정 행 현재 상태·역할) →
 * domain 전이 → 역할 이벤트·코드·계정 갱신 → 감사(같은 TX). 거부·거절은 감사 1행만 커밋한다(상태 변경 없음).
 * 입력 형식 오류(actor ID·login_name·표시명·역할 이름)는 요청 검증 실패라 TX·감사 없이 `INVALID_INPUT`(웹 400).
 * 재시도: 업무 UNIQUE(login_name) 경합·1213·1205는 전체 rollback 뒤 새 TX에서 처음부터 재판정(최대 3회 — 재판정하면 `LOGIN_NAME_TAKEN`으로 수렴).
 */
@Service
class AdminAccountCommandService(
    transactionManager: PlatformTransactionManager,
    private val accounts: AdminAccountRepository,
    private val roleEvents: AdminRoleEventStore,
    private val codes: EnrollmentCodeStore,
    private val codeGenerator: EnrollmentCodeGenerator,
    private val ids: AdminIdGenerator,
    private val clock: AdminClock,
    private val settings: AdminSecuritySettings,
    recorder: AdminAuditRecorder,
) : AdminAccountCommandUseCase {

    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val audit = AccountAuditWriter(recorder)

    override fun createAccount(command: CreateAdminAccount): AccountCommandResult {
        val requesterId = parseActor(command.requesterActorId) ?: return INVALID
        val loginName = parse { LoginName(command.loginName) } ?: return INVALID
        val displayName = parse { DisplayName(command.displayName) } ?: return INVALID
        return inTransaction {
            val now = now()
            val requester = accounts.lock(requesterId)?.value
            if (requester == null || !AdminPermission.allows(requester, AdminAction.CREATE_ACCOUNT)) {
                return@inTransaction deny(now, requesterId, requester, ACCOUNT_CREATED, command.context)
            }
            if (accounts.findByLoginName(loginName) != null) {
                return@inTransaction reject(now, requesterId, requester, ACCOUNT_CREATED, command.context, null, LOGIN_NAME_TAKEN)
            }
            val issueId = ids.newEnrollIssueId()
            val account = AdminAccount.create(ids.newActorId(), loginName, displayName, CredentialBasis.oneShotCommand(issueId, requesterId), now)
            accounts.insert(account)
            val code = issueCode(account, now)
            audit.admin(now, requesterId.value, requester, ACCOUNT_CREATED, SUCCESS, command.context, account.id.value)
            audit.admin(now, requesterId.value, requester, ENROLLMENT_CODE_ISSUED, SUCCESS, command.context, account.id.value, "purpose=ENROLL")
            AccountCommandResult.Succeeded(account.id.value, code)
        }
    }

    override fun reissueEnrollCode(command: TargetedAccountCommand): AccountCommandResult =
        targeted(command, AdminAction.REISSUE_ENROLL_CODE, ENROLLMENT_CODE_ISSUED) { requester, target, now ->
            when (val change = target.value.reissueEnrollCode(ids.newEnrollIssueId(), requester.id, now)) {
                is AccountChange.Rejected -> reject(now, requester.id, requester, ENROLLMENT_CODE_ISSUED, command.context, target.value.id, change.reason.name)
                is AccountChange.Applied -> {
                    val revoked = codes.revokeUnused(target.value.id, now)
                    accounts.update(target, change.account)
                    val code = issueCode(change.account, now)
                    audit.admin(now, requester.id.value, requester, ENROLLMENT_CODE_ISSUED, SUCCESS, command.context, target.value.id.value, "purpose=ENROLL;revokedCodes=${revoked.size}")
                    AccountCommandResult.Succeeded(target.value.id.value, code)
                }
            }
        }

    override fun suspend(command: TargetedAccountCommand): AccountCommandResult =
        targeted(command, AdminAction.SUSPEND_ACCOUNT, ACCOUNT_SUSPENDED) { requester, target, now ->
            when (val change = target.value.suspend(requester.id, now)) {
                is AccountChange.Rejected -> reject(now, requester.id, requester, ACCOUNT_SUSPENDED, command.context, target.value.id, change.reason.name)
                is AccountChange.Applied -> {
                    // H-B: 정지 TX가 대상의 미사용 등록 코드를 모두 폐기한다(정지 해제가 코드를 되살리지 않는다).
                    val revoked = codes.revokeUnused(target.value.id, now)
                    accounts.update(target, change.account)
                    audit.admin(now, requester.id.value, requester, ACCOUNT_SUSPENDED, SUCCESS, command.context, target.value.id.value, suspensionDetail(revoked))
                    AccountCommandResult.Succeeded(target.value.id.value)
                }
            }
        }

    override fun revokeRole(command: RevokeAdminRole): AccountCommandResult {
        val role = AdminRole.entries.firstOrNull { it.name == command.role } ?: return INVALID
        return targeted(TargetedAccountCommand(command.requesterActorId, command.targetActorId, command.context), AdminAction.REVOKE_ROLE, ROLE_REVOKED) { requester, target, now ->
            when (val change = target.value.revokeRole(requester.id, role, now)) {
                is AccountChange.Rejected -> reject(now, requester.id, requester, ROLE_REVOKED, command.context, target.value.id, change.reason.name)
                is AccountChange.Applied -> {
                    roleEvents.append(change.events)
                    accounts.update(target, change.account)
                    audit.admin(now, requester.id.value, requester, ROLE_REVOKED, SUCCESS, command.context, target.value.id.value, "role=${role.name}")
                    AccountCommandResult.Succeeded(target.value.id.value)
                }
            }
        }
    }

    /** 요청자·대상 잠금(오름차순) → 권한 → 대상 존재 → [block]. */
    private fun targeted(
        command: TargetedAccountCommand,
        action: AdminAction,
        auditAction: String,
        block: (AdminAccount, Versioned<AdminAccount>, Instant) -> AccountCommandResult,
    ): AccountCommandResult {
        val requesterId = parseActor(command.requesterActorId) ?: return INVALID
        val targetId = parseActor(command.targetActorId) ?: return INVALID
        return inTransaction {
            val now = now()
            val locked = accounts.lockAll(listOf(requesterId, targetId))
            val requester = locked[requesterId]?.value
            if (requester == null || !AdminPermission.allows(requester, action)) {
                return@inTransaction deny(now, requesterId, requester, auditAction, command.context)
            }
            val target = locked[targetId] ?: return@inTransaction reject(now, requesterId, requester, auditAction, command.context, null, TARGET_NOT_FOUND)
            block(requester, target, now)
        }
    }

    /** 새 `ENROLL` 코드 발급(같은 TX — 대상당 활성 1개는 호출자가 먼저 폐기). 원문은 반환값으로 1회만. */
    private fun issueCode(account: AdminAccount, now: Instant): String {
        val issued = codeGenerator.newCode()
        codes.insert(EnrollmentCode.issue(issued.hash, account.id, requireNotNull(account.pending), now, now.plus(settings.enrollmentCodeValidity)))
        return issued.displayCode
    }

    /** 정지 감사 detail: 폐기 수 + 폐기한 코드의 발급 근거(최대 [MAX_AUDITED_BASES]개, 넘으면 `revokedBasesTruncated=true` — 500자 상한 안). */
    private fun suspensionDetail(bases: List<String>): String = buildString {
        append("revokedCodes=${bases.size}")
        if (bases.isNotEmpty()) append(";revokedBases=").append(bases.take(MAX_AUDITED_BASES).joinToString(","))
        if (bases.size > MAX_AUDITED_BASES) append(";revokedBasesTruncated=true")
    }

    private fun deny(now: Instant, requesterId: AdminActorId, requester: AdminAccount?, action: String, context: AdminRequestContext): AccountCommandResult {
        audit.admin(now, requesterId.value, requester, action, DENIED, context)
        return AccountCommandResult.Denied
    }

    @Suppress("LongParameterList")
    private fun reject(
        now: Instant,
        requesterId: AdminActorId,
        requester: AdminAccount,
        action: String,
        context: AdminRequestContext,
        target: AdminActorId?,
        code: String,
    ): AccountCommandResult {
        audit.admin(now, requesterId.value, requester, action, REJECTED, context, target?.value, reason(code))
        return AccountCommandResult.Rejected(code)
    }

    private fun inTransaction(block: () -> AccountCommandResult): AccountCommandResult = retryLaterOnConflict { transactions.execute(::isRetryable) { block() } }

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    private fun parseActor(value: String): AdminActorId? = parse { AdminActorId(value) }

    private inline fun <T> parse(block: () -> T): T? = try {
        block()
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun isRetryable(e: RuntimeException): Boolean = when (e) {
        is PersistenceFailure.UniqueConflict -> e.constraint.businessKey
        is PersistenceFailure.Deadlock, is PersistenceFailure.LockTimeout -> true
        else -> false
    }

    private companion object {
        const val MAX_ATTEMPTS = 3

        /** 근거 최대 40자 × 8 + 구분자 + 접두 ≈ 380자 < detail 500자. 대상당 활성 코드는 1개라 보통 1개. */
        const val MAX_AUDITED_BASES = 8
        const val ACCOUNT_CREATED = "ACCOUNT_CREATED"
        const val ACCOUNT_SUSPENDED = "ACCOUNT_SUSPENDED"
        const val ROLE_REVOKED = "ROLE_REVOKED"
        const val ENROLLMENT_CODE_ISSUED = "ENROLLMENT_CODE_ISSUED"
        const val LOGIN_NAME_TAKEN = "LOGIN_NAME_TAKEN"
        const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
        val INVALID = AccountCommandResult.Rejected("INVALID_INPUT")
    }
}
