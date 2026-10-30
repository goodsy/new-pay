package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.BootstrapCommand
import com.paycore.admin.account.application.provided.IssuedCodeLine
import com.paycore.admin.account.application.provided.NewAdminAccount
import com.paycore.admin.account.application.provided.OneShotCommand
import com.paycore.admin.account.application.provided.OneShotCommandUseCase
import com.paycore.admin.account.application.provided.OneShotResult
import com.paycore.admin.account.application.provided.RecoveryCommand
import com.paycore.admin.account.application.required.AdminAccountMetrics
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminClock
import com.paycore.admin.account.application.required.AdminIdGenerator
import com.paycore.admin.account.application.required.AdminRoleEventStore
import com.paycore.admin.account.application.required.AdminSecuritySettings
import com.paycore.admin.account.application.required.BootstrapStore
import com.paycore.admin.account.application.required.EnrollmentCodeGenerator
import com.paycore.admin.account.application.required.EnrollmentCodeStore
import com.paycore.admin.account.application.required.PersistenceFailure
import com.paycore.admin.account.application.required.RecoveryEpisode
import com.paycore.admin.account.application.required.RecoveryEpisodeStore
import com.paycore.admin.account.application.required.RecoverySettings
import com.paycore.admin.account.application.required.StoreConstraint
import com.paycore.admin.account.domain.AccountChange
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.CredentialBasis
import com.paycore.admin.account.domain.DisplayName
import com.paycore.admin.account.domain.EnrollmentCode
import com.paycore.admin.account.domain.EnrollmentPurpose
import com.paycore.admin.account.domain.LoginName
import com.paycore.admin.account.domain.RecoveryBasis
import com.paycore.admin.account.domain.RoleEventActor
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [OneShotCommandUseCase](§4.9). 로컬 TX 1회(재시도 없음 — 경합 패자는 비영 exit로 끝나고 운영자가 상황을 다시 본다). 잠금 순서 §4.13:
 * 부트스트랩 = 새 행 INSERT만(고정 행 PK가 1회성 권위), 복구 = 회차(만료 회차 종료 UPDATE·새 회차 INSERT) → 대상 계정(오름차순) → 코드 행.
 * 감사 행위자 = `SYSTEM_BOOTSTRAP`·`SYSTEM_RECOVERY`(코드 원문 없음). 거부·패배 감사는 rollback된 주 TX 밖 별도 TX.
 */
@Service
class OneShotCommandService(
    transactionManager: PlatformTransactionManager,
    private val accounts: AdminAccountRepository,
    private val roleEvents: AdminRoleEventStore,
    private val codes: EnrollmentCodeStore,
    private val codeGenerator: EnrollmentCodeGenerator,
    private val bootstrapStore: BootstrapStore,
    private val episodes: RecoveryEpisodeStore,
    private val ids: AdminIdGenerator,
    private val clock: AdminClock,
    private val settings: AdminSecuritySettings,
    private val recoverySettings: RecoverySettings,
    private val recorder: AdminAuditRecorder,
    private val metrics: AdminAccountMetrics,
) : OneShotCommandUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    override fun bootstrap(command: BootstrapCommand): OneShotResult = observed(OneShotCommand.BOOTSTRAP) { bootstrapStep(command) }

    override fun recover(command: RecoveryCommand): OneShotResult = observed(OneShotCommand.RECOVERY) { recoverStep(command) }

    /**
     * S8 지표(`paycore.admin.oneshot{command,result}`). 1회성 명령은 짧은 비웹 프로세스라(관리 포트 없음) 이 지표는 push 수집기를 붙일 때만 보인다 —
     * 기본 관측은 종료 코드·감사·명령 줄의 결과 로그 한 줄(OneShotCommandLine)이다.
     */
    private inline fun observed(command: OneShotCommand, block: () -> OneShotResult): OneShotResult {
        val result = try {
            block()
        } catch (e: RuntimeException) {
            metrics.oneShot(command.name, "ERROR")
            throw e
        }
        metrics.oneShot(command.name, resultName(result))
        return result
    }

    private fun bootstrapStep(command: BootstrapCommand): OneShotResult {
        // ADMIN-BOOT-002(D-ADMIN1-022): SA-A·SA-B·APPROVER — login·표시명 모두 서로 달라야 한다(I-3).
        val (saA, saB, approver) = parseDistinct(listOf(command.securityAdmin, command.secondSecurityAdmin, command.approver)) ?: return OneShotResult.Invalid(INVALID_INPUT)
        return guarded(OneShotCommand.BOOTSTRAP, command.correlationId, ALREADY_BOOTSTRAPPED, StoreConstraint.BOOTSTRAP) {
            transactions.execute({ false }) {
                val now = now()
                if (accounts.anyExists()) throw Refusal(ACCOUNTS_EXIST)
                val (saId, saBId, apId) = Triple(ids.newActorId(), ids.newActorId(), ids.newActorId())
                // 고정 행 열은 SA-A·APPROVER 그대로(migration 없음 — I-4). SA-B는 같은 TX의 감사 ACCOUNT_CREATED·역할 이벤트(SYSTEM_BOOTSTRAP)로 추적한다.
                bootstrapStore.markExecuted(now, saId, apId)
                val expiresAt = now.plus(settings.enrollmentCodeValidity)
                val lines = listOf(
                    createPrivileged(saId, saA, AdminRole.SECURITY_ADMIN, RoleEventActor.SystemBootstrap, null, expiresAt, now, command.correlationId),
                    createPrivileged(saBId, saB, AdminRole.SECURITY_ADMIN, RoleEventActor.SystemBootstrap, null, expiresAt, now, command.correlationId),
                    createPrivileged(apId, approver, AdminRole.APPROVER, RoleEventActor.SystemBootstrap, null, expiresAt, now, command.correlationId),
                )
                audit(now, BOOTSTRAP_KIND, BOOTSTRAP, SUCCESS, command.correlationId, null, "accounts=${lines.size}")
                OneShotResult.Done(null, lines)
            }
        }
    }

    private fun recoverStep(command: RecoveryCommand): OneShotResult {
        val logins = command.reissueLoginNames.map { runCatching { LoginName(it) }.getOrNull() ?: return OneShotResult.Invalid(INVALID_INPUT) }
        val pair = command.newPair?.let { parsePair(it) ?: return OneShotResult.Invalid(INVALID_INPUT) }
        if (logins.isEmpty() && pair == null) return OneShotResult.Invalid("NOTHING_TO_DO")
        val newLogins = pair?.let { listOf(it.first.first, it.second.first) }.orEmpty()
        if ((logins + newLogins).toSet().size != logins.size + newLogins.size) return OneShotResult.Invalid(INVALID_INPUT)
        return guarded(OneShotCommand.RECOVERY, command.correlationId, EPISODE_TAKEN, StoreConstraint.RECOVERY_EPISODE) {
            transactions.execute({ false }) {
                val now = now()
                // 1) 회차: 열린 회차가 만료됐거나 쓸 수 있는 코드가 0이면(S6 리뷰 M-2 — 잘못된 대상·정지·재설정으로 모두 폐기) 조건부 UPDATE로 닫고 진행,
                //    아직 쓸 수 있는 코드가 있으면 거부. 닫기 UPDATE가 0행이면(동시 실행이 먼저 닫고 새 회차를 엶 — S6 리뷰 m-1) 경합 패배로 본다.
                //    권위는 열린 회차 UNIQUE. 잠금 순서: 회차(UPDATE) → 계정 → 코드(집계는 비잠금 읽기).
                episodes.findOpen()?.let { open ->
                    val dead = !open.isOpenAt(now) || codes.countUsableForEpisode(open.episodeNo, now) == 0
                    if (!dead) throw Refusal(EPISODE_OPEN)
                    if (episodes.closeIfOpen(open.episodeNo, now)) {
                        audit(now, RECOVERY_KIND, "RECOVERY_EPISODE_CLOSED", SUCCESS, command.correlationId, null, "episode=${open.episodeNo};reason=${if (open.isOpenAt(now)) "NO_USABLE_CODE" else "EXPIRED"}")
                    } else {
                        val again = episodes.findOpen()
                        if (again != null && again.episodeNo != open.episodeNo) throw PersistenceFailure.UniqueConflict(StoreConstraint.RECOVERY_EPISODE)
                        if (again != null) throw Refusal(EPISODE_OPEN)
                    }
                }
                // 2) 조건(D-ADMIN1-010): 활성 SECURITY_ADMIN 0명 또는 활성 APPROVER 0명.
                if (accounts.countActiveHolders(AdminRole.SECURITY_ADMIN) > 0 && accounts.countActiveHolders(AdminRole.APPROVER) > 0) throw Refusal(NOT_NEEDED)
                val targets = logins.map { accounts.findByLoginName(it)?.value?.id ?: throw Refusal(TARGET_NOT_FOUND) }
                // 3) 새 회차(번호 = 최대 + 1) — 동시 실행 패자는 여기서 1062.
                val episode = RecoveryEpisode(episodes.nextEpisodeNo(), now, now.plus(recoverySettings.episodeValidity), null)
                episodes.open(episode)
                val basis = RecoveryBasis.of(episode.episodeNo)
                // 4) 작업 A: 대상 계정 오름차순 잠금 → 보유 SA·APPROVER 기록 → 전 역할 정지(재append 포함) → 미사용 코드 폐기 → RECOVERY 코드(만료 = 회차 만료).
                val lines = mutableListOf<IssuedCodeLine>()
                accounts.lockAll(targets).values.sortedBy { it.value.id }.forEach { current ->
                    val account = current.value
                    val recorded = account.recoveryRecordableRoles
                    val change = account.beginRecovery(basis, now) as? AccountChange.Applied ?: throw Refusal(TARGET_NOT_ELIGIBLE)
                    roleEvents.append(change.events)
                    val revoked = codes.revokeUnused(account.id, now)
                    accounts.update(current, change.account)
                    val issued = codeGenerator.newCode()
                    codes.insert(EnrollmentCode(issued.hash, account.id, EnrollmentPurpose.RECOVERY, basis, episode.episodeNo, recorded, now, episode.expiresAt, null, null))
                    lines += IssuedCodeLine(account.loginName.value, EnrollmentPurpose.RECOVERY.name, issued.displayCode)
                    audit(
                        now, RECOVERY_KIND, ENROLLMENT_CODE_ISSUED, SUCCESS, command.correlationId, account.id.value,
                        "purpose=RECOVERY;episode=${episode.episodeNo};recorded=${recorded.sortedBy { it.ordinal }.joinToString(",") { it.name }.ifEmpty { "none" }};revokedCodes=${revoked.size}",
                    )
                }
                if (lines.size != targets.size) throw Refusal(TARGET_NOT_FOUND)
                // 5) 작업 B: 새 SECURITY_ADMIN·APPROVER 쌍(역할 생성 시 부여, 코드 만료 = 회차 만료, 회차 번호 기록).
                pair?.let { (sa, ap) ->
                    lines += createPrivileged(ids.newActorId(), sa, AdminRole.SECURITY_ADMIN, RoleEventActor.SystemRecovery, episode.episodeNo, episode.expiresAt, now, command.correlationId)
                    lines += createPrivileged(ids.newActorId(), ap, AdminRole.APPROVER, RoleEventActor.SystemRecovery, episode.episodeNo, episode.expiresAt, now, command.correlationId)
                }
                audit(now, RECOVERY_KIND, RECOVERY, SUCCESS, command.correlationId, null, "episode=${episode.episodeNo};taskA=${targets.size};taskB=${if (pair == null) 0 else 2}")
                OneShotResult.Done(episode.episodeNo, lines)
            }
        }
    }

    override fun recordRefusal(command: OneShotCommand, reason: String, correlationId: String) {
        transactions.execute({ false }) { audit(now(), kindOf(command), command.name, REFUSED, correlationId, null, "reason=$reason") }
    }

    /** 시스템 경로 계정 생성: 시스템 근거(관리자 배제 없음 — n-H), 역할 부여(주체 = 시스템), ENROLL 코드(근거 = 발급 ID, 복구 B는 회차 번호). */
    @Suppress("LongParameterList")
    private fun createPrivileged(
        id: AdminActorId, input: Pair<LoginName, DisplayName>, role: AdminRole, actor: RoleEventActor, episodeNo: Long?, expiresAt: Instant, now: Instant, correlationId: String,
    ): IssuedCodeLine {
        val issueId = ids.newEnrollIssueId()
        val granted = AdminAccount.create(id, input.first, input.second, CredentialBasis.system(issueId), now).grantRole(role, actor, null, now) as AccountChange.Applied
        try {
            accounts.insert(granted.account)
        } catch (e: PersistenceFailure.UniqueConflict) {
            if (e.constraint == StoreConstraint.LOGIN_NAME) throw Refusal(LOGIN_NAME_TAKEN) else throw e
        }
        roleEvents.append(granted.events)
        val issued = codeGenerator.newCode()
        codes.insert(EnrollmentCode(issued.hash, id, EnrollmentPurpose.ENROLL, issueId, episodeNo, null, now, expiresAt, null, null))
        val kind = if (actor == RoleEventActor.SystemBootstrap) BOOTSTRAP_KIND else RECOVERY_KIND
        audit(now, kind, ACCOUNT_CREATED, SUCCESS, correlationId, id.value, "role=${role.name}")
        audit(now, kind, ENROLLMENT_CODE_ISSUED, SUCCESS, correlationId, id.value, "purpose=ENROLL" + (episodeNo?.let { ";episode=$it" } ?: ""))
        return IssuedCodeLine(input.first.value, EnrollmentPurpose.ENROLL.name, issued.displayCode)
    }

    /** 거부(별도 TX 감사)·경합 패배(1062 — 별도 TX 감사)·1213/1205(변경 없음). */
    private fun guarded(command: OneShotCommand, correlationId: String, lostReason: String, lostConstraint: StoreConstraint, block: () -> OneShotResult): OneShotResult = try {
        block()
    } catch (refusal: Refusal) {
        separateAudit(command, REFUSED, correlationId, refusal.reason)
        OneShotResult.Refused(refusal.reason)
    } catch (e: PersistenceFailure.UniqueConflict) {
        if (e.constraint != lostConstraint) throw e
        separateAudit(command, LOST, correlationId, lostReason)
        OneShotResult.Lost
    } catch (_: PersistenceFailure.Deadlock) {
        OneShotResult.RetryLater
    } catch (_: PersistenceFailure.LockTimeout) {
        OneShotResult.RetryLater
    }

    private fun separateAudit(command: OneShotCommand, result: String, correlationId: String, reason: String) {
        transactions.execute({ false }) { audit(now(), kindOf(command), command.name, result, correlationId, null, "reason=$reason") }
    }

    @Suppress("LongParameterList")
    private fun audit(now: Instant, kind: String, action: String, result: String, correlationId: String, target: String?, detail: String) {
        recorder.record(
            AuditRecord(
                occurredAt = now, actorKind = kind, actorId = null, actorDisplayName = null, action = action, resultCode = result, correlationId = correlationId,
                targetActorId = target, detail = detail,
            ),
        )
    }

    private fun parsePair(pair: Pair<NewAdminAccount, NewAdminAccount>): Pair<Pair<LoginName, DisplayName>, Pair<LoginName, DisplayName>>? {
        val (a, b) = pair.toList().map { input ->
            runCatching { LoginName(input.loginName) to DisplayName(input.displayName) }.getOrNull() ?: return null
        }
        return if (a.first == b.first) null else a to b
    }

    /** 부트스트랩 입력: 형식 + login 모두 다름 + 표시명 모두 다름(SA 두 명을 감사·승인 화면 스냅샷에서 구별 — I-3). 하나라도 어기면 null. */
    private fun parseDistinct(inputs: List<NewAdminAccount>): List<Pair<LoginName, DisplayName>>? {
        val parsed = inputs.map { input -> runCatching { LoginName(input.loginName) to DisplayName(input.displayName) }.getOrNull() ?: return null }
        val distinct = parsed.map { it.first }.toSet().size == parsed.size && parsed.map { it.second }.toSet().size == parsed.size
        return if (distinct) parsed else null
    }

    private fun kindOf(command: OneShotCommand) = if (command == OneShotCommand.BOOTSTRAP) BOOTSTRAP_KIND else RECOVERY_KIND

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    private class Refusal(val reason: String) : RuntimeException(null, null, false, false)

    /** 결과 분류 이름(지표 label — 고정 값). */
    private fun resultName(result: OneShotResult): String = when (result) {
        is OneShotResult.Done -> "DONE"
        is OneShotResult.Refused -> "REFUSED"
        OneShotResult.Lost -> "LOST"
        OneShotResult.RetryLater -> "RETRY_LATER"
        is OneShotResult.Invalid -> "INVALID"
    }

    private companion object {
        const val BOOTSTRAP_KIND = "SYSTEM_BOOTSTRAP"
        const val RECOVERY_KIND = "SYSTEM_RECOVERY"
        const val BOOTSTRAP = "BOOTSTRAP"
        const val RECOVERY = "RECOVERY"
        const val ACCOUNT_CREATED = "ACCOUNT_CREATED"
        const val ENROLLMENT_CODE_ISSUED = "ENROLLMENT_CODE_ISSUED"
        const val SUCCESS = "SUCCESS"
        const val REFUSED = "REFUSED"
        const val LOST = "LOST"
        const val INVALID_INPUT = "INVALID_INPUT"
        const val ACCOUNTS_EXIST = "ACCOUNTS_EXIST"
        const val ALREADY_BOOTSTRAPPED = "ALREADY_BOOTSTRAPPED"
        const val EPISODE_TAKEN = "EPISODE_TAKEN"
        const val EPISODE_OPEN = "EPISODE_OPEN"
        const val NOT_NEEDED = "NOT_NEEDED"
        const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
        const val TARGET_NOT_ELIGIBLE = "TARGET_NOT_ELIGIBLE"
        const val LOGIN_NAME_TAKEN = "LOGIN_NAME_TAKEN"
    }
}
