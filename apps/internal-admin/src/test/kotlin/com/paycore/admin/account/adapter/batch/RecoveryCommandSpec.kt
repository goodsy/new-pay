package com.paycore.admin.account.adapter.batch

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.EnrollmentFailure
import com.paycore.admin.account.application.provided.EnrollmentResult
import com.paycore.admin.account.application.provided.EnrollmentUseCase
import com.paycore.admin.account.application.provided.NewAdminAccount
import com.paycore.admin.account.application.provided.OneShotCommandUseCase
import com.paycore.admin.account.application.provided.OneShotResult
import com.paycore.admin.account.application.provided.RecoveryCommand
import com.paycore.admin.account.application.provided.RevokeAdminRole
import com.paycore.admin.account.application.required.AdminRoleEventStore
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.EnrollmentCodeHash
import com.paycore.admin.account.domain.RecoveryBasis
import com.paycore.admin.account.domain.RoleEventType
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.RequestApproval
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.RowLockHolder
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import com.paycore.admin.account.adapter.integration.MicrometerAdminAccountMetrics
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 오프라인 복구(§4.9.2 — `RecoveryCommandSpec`·`RecoveryConcurrencySpec`·`RecoveryRoleRestoreSpec` ①~⑦, D-ADMIN1-010·011·012, D-99) + `ApprovalLockOrderSpec`의 복구 명령 행
 * (S5 이월 — 1205·1213 → 고유 비영 exit, 무변경). 전용 컨텍스트(0명 조건 — 시험마다 표를 비운다, 이 컨텍스트만 `innodb_lock_wait_timeout = 3`).
 */
// 회차 유효를 코드 유효(24h)와 다르게(2h) 두어 "작업 B 코드 만료 = 회차 만료"(⑦)가 관측되게 한다.
@SpringBootTest(
    properties = [
        "paycore.test.context=one-shot-recovery", "spring.datasource.hikari.data-source-properties.sessionVariables=innodb_lock_wait_timeout=3",
        "paycore.admin.recovery.episode-validity=2h",
    ],
)
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class RecoveryCommandSpec(
    private val oneShot: OneShotCommandUseCase,
    private val storage: AdminAccountStorage,
    private val commands: AdminAccountCommandUseCase,
    private val enrollment: EnrollmentUseCase,
    private val roleEvents: AdminRoleEventStore,
    private val path: ApprovalPath,
    private val approvals: ApprovalUseCase,
    private val container: MySQLContainer,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    beforeEach { OneShotSupport.wipe(container) }
    fun scalarOf(sql: String, vararg args: Any?): Any? = AdminDbAccounts.root(container).use { it.scalar(sql, *args) }
    fun intOf(sql: String, vararg args: Any?): Int = (scalarOf(sql, *args) as Number).toInt()
    fun openEpisodes(): Int = intOf("SELECT COUNT(*) FROM admin_recovery_episode WHERE closed_at IS NULL")
    fun recover(vararg logins: String, pair: Pair<NewAdminAccount, NewAdminAccount>? = null): OneShotResult =
        oneShot.recover(RecoveryCommand(logins.toList(), pair, OneShotSupport.context().correlationId))
    fun done(result: OneShotResult): OneShotResult.Done = result.shouldBeInstanceOf<OneShotResult.Done>()
    fun codeOf(result: OneShotResult.Done, login: String): String = result.codes.single { it.loginName == login }.code
    fun save(account: AdminAccount): AdminAccount = storage.save(account)
    /**
     * 역할 보유 ACTIVE 계정을 **domain 2인 승인 경로**(Fixture 요청자·승인자)로 만들어 저장한다 — 실제 승인 Use Case(`ApprovalPath`)는 매번 부트스트랩 SECURITY_ADMIN과
     * APPROVER를 새로 만들어 활성 SA·APPROVER 수를 늘리므로, "활성 0명"이 조건인 복구 시험에서는 쓸 수 없다(이름에 의도 — S6 리뷰 m-3). 역할 이벤트(재생 원천)도
     * 같은 domain 경로 값으로 기록한다(형식이 맞는 고정 승인 ID).
     */
    fun rolesGrantedViaDomainApprovalPath_noLiveApprovers(vararg roles: AdminRole): AdminAccount = save(AdminAccountFixture.withRolesViaApproval(*roles)).also { a ->
        storage.inTx {
            roleEvents.append(
                roles.map {
                    com.paycore.admin.account.domain.RoleEvent(
                        a.id, it, RoleEventType.GRANT, com.paycore.admin.account.domain.RoleEventActor.Admin(AdminAccountFixture.APPROVAL_APPROVER), FIXTURE_APPROVAL_ID, null,
                        AdminAccountFixture.NOW,
                    )
                },
            )
        }
    }
    fun withRoles(vararg roles: AdminRole): AdminAccount = rolesGrantedViaDomainApprovalPath_noLiveApprovers(*roles)
    /** 시험 시작 뒤 모든 감사 행(코드 원문이 없는지 — S6 리뷰 m-4). */
    fun allAuditText(): List<String> = AdminDbAccounts.root(container).use {
        it.column("SELECT CONCAT_WS('|', actor_kind, action, result_code, COALESCE(detail, ''), COALESCE(reason, ''), correlation_id) FROM admin_audit_event")
    }
    fun lastEventOf(id: AdminActorId, role: AdminRole) = storage.inTx { roleEvents.history(id) }.last { it.role == role }
    val pair = NewAdminAccount("rec-sa", "복구 보안") to NewAdminAccount("rec-apr", "복구 승인")

    Given("the recovery condition (D-ADMIN1-010: active SECURITY_ADMIN = 0 or active APPROVER = 0)") {
        Then("both present → refused without an episode; the refusal is audited") {
            storage.securityAdmin()
            storage.bootstrapped(AdminRole.APPROVER)
            val refusals = { registry.find(MicrometerAdminAccountMetrics.ONE_SHOT).tags("command", "RECOVERY", "result", "REFUSED").counter()?.count() ?: 0.0 }
            val before = refusals()
            recover(pair = pair) shouldBe OneShotResult.Refused("NOT_NEEDED")
            // S8: paycore.admin.oneshot{command,result} — 비웹 프로세스라 수집은 push 수집기를 붙일 때만(기본 관측은 종료 코드·감사).
            refusals() shouldBe before + 1
            openEpisodes() shouldBe 0
            AdminDbAccounts.root(container).use { it.column("SELECT CONCAT(actor_kind, ':', action, ':', result_code, ':', detail) FROM admin_audit_event") } shouldBe
                listOf("SYSTEM_RECOVERY:RECOVERY:REFUSED:reason=NOT_NEEDED")
        }
    }

    Given("task A on the only SECURITY_ADMIN holder (also OPERATOR)") {
        Then("records {SECURITY_ADMIN}, suspends all roles (cause = episode), issues a RECOVERY code expiring with the episode; completion restores SECURITY_ADMIN only and closes the episode") {
            val target = withRoles(AdminRole.SECURITY_ADMIN, AdminRole.OPERATOR)
            val result = done(recover(target.loginName.value))
            val episode = requireNotNull(result.episodeNo)
            val basis = RecoveryBasis.of(episode)
            val recovering = storage.load(target.id)
            listOf(recovering.status, recovering.pending?.basis, recovering.activeRoles, recovering.suspendedRoles, recovering.credentialBasis.issuers) shouldBe
                listOf(AccountStatus.PENDING_ENROLLMENT, basis, emptySet<AdminRole>(), setOf(AdminRole.SECURITY_ADMIN, AdminRole.OPERATOR), emptySet<AdminActorId>())
            val code = codeOf(result, target.loginName.value)
            val row = storage.code(EnrollmentCodeHash.of(code))!!
            listOf(row.recoveryEpisode, row.recoveryRoles, row.expiresAt) shouldBe
                listOf(episode, setOf(AdminRole.SECURITY_ADMIN), scalarOf("SELECT expires_at FROM admin_recovery_episode WHERE episode_no = ?", episode).let { (it as java.time.LocalDateTime).toInstant(java.time.ZoneOffset.UTC) })
            OneShotSupport.enroll(enrollment, target.id.value, code)
            storage.load(target.id).let { listOf(it.status, it.activeRoles, it.suspendedRoles) } shouldBe
                listOf(AccountStatus.ACTIVE, setOf(AdminRole.SECURITY_ADMIN), setOf(AdminRole.OPERATOR))
            lastEventOf(target.id, AdminRole.SECURITY_ADMIN).let { listOf(it.type, it.cause) } shouldBe listOf(RoleEventType.REACTIVATE, basis)
            openEpisodes() shouldBe 0
            allAuditText().none { it.contains(code) } shouldBe true
            // 반복(D-ADMIN1-010): 다시 0명(APPROVER 없음)이면 다음 회차가 열린다.
            done(recover(pair = pair)).episodeNo shouldBe episode + 1
        }
        Then("⑥ D-99: an APPROVER already suspended by a reset gets a SUSPEND re-appended with cause = episode; it is restored on completion and the old reset can no longer be cited") {
            val admin = storage.securityAdmin()
            val target = path.withRolesViaApproval(AdminRole.APPROVER)
            val (_, _, resetId) = path.credentialReset("PASSWORD_RESET", target.id)
            // 승인 경로가 만든 다른 APPROVER를 회수해 활성 APPROVER = 0으로 만든다(복구 조건).
            AdminDbAccounts.root(container).use {
                it.column("SELECT actor_id FROM admin_account WHERE status = 'ACTIVE' AND FIND_IN_SET('APPROVER', active_roles) > 0")
            }.forEach { commands.revokeRole(RevokeAdminRole(admin.id.value, it, "APPROVER", OneShotSupport.context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>() }
            val result = done(recover(target.loginName.value))
            val basis = RecoveryBasis.of(requireNotNull(result.episodeNo))
            lastEventOf(target.id, AdminRole.APPROVER).let { listOf(it.type, it.cause) } shouldBe listOf(RoleEventType.SUSPEND, basis)
            OneShotSupport.enroll(enrollment, target.id.value, codeOf(result, target.loginName.value))
            storage.load(target.id).activeRoles shouldBe setOf(AdminRole.APPROVER)
            val other = storage.securityAdmin()
            approvals.request(RequestApproval(other.id.value, other.sessionGeneration, "ROLE_REACTIVATE", target.id.value, null, resetId, OneShotSupport.context())) shouldBe
                ApprovalOutcome.Rejected("NOTHING_TO_REACTIVATE")
        }
    }

    Given("role restore conditions at completion (RecoveryRoleRestore)") {
        Then("① a task-B APPROVER enrolls first (active APPROVER > 0) → task A completion does not restore APPROVER; ⑦ task-B codes expire with the episode") {
            // 활성 SECURITY_ADMIN 0명(조건 성립) — 대상은 유일한 APPROVER 보유자.
            val target = withRoles(AdminRole.APPROVER)
            val result = done(recover(target.loginName.value, pair = pair))
            val episodeExpiry = scalarOf("SELECT expires_at FROM admin_recovery_episode WHERE episode_no = ?", result.episodeNo)
            result.codes.filter { it.loginName.startsWith("rec-") }.forEach { line ->
                scalarOf("SELECT expires_at FROM admin_enrollment_code WHERE code_hash = ?", EnrollmentCodeHash.of(line.code).bytes) shouldBe episodeExpiry
            }
            val newApprover = AdminDbAccounts.root(container).use { it.scalar("SELECT actor_id FROM admin_account WHERE login_name = 'rec-apr'") as String }
            OneShotSupport.enroll(enrollment, newApprover, codeOf(result, "rec-apr"))
            OneShotSupport.enroll(enrollment, target.id.value, codeOf(result, target.loginName.value))
            storage.load(target.id).let { it.activeRoles to it.suspendedRoles } shouldBe (emptySet<AdminRole>() to setOf(AdminRole.APPROVER))
            openEpisodes() shouldBe 1
            allAuditText().none { row -> result.codes.any { row.contains(it.code) } } shouldBe true
        }
        Then("② two codes of the same episode completed at once are serialized by the episode row: the last one closes it, no role is restored twice") {
            val a = withRoles(AdminRole.SECURITY_ADMIN)
            val b = withRoles(AdminRole.SECURITY_ADMIN, AdminRole.VIEWER)
            val result = done(recover(a.loginName.value, b.loginName.value))
            val drafts = listOf(a, b).map { acc ->
                var draft = (enrollment.begin(codeOf(result, acc.loginName.value)) as EnrollmentResult.InProgress).draft
                draft = (enrollment.providePassword(draft, "one-shot-password-1".toCharArray()) as EnrollmentResult.InProgress).draft
                val p = enrollment.startTotp(draft) as EnrollmentResult.TotpProvisioning
                p.draft to com.paycore.admin.support.TestTotp.code(com.paycore.admin.support.TestTotp.secretOf(p.otpauthUri))
            }
            val pool = Executors.newFixedThreadPool(2)
            val outcomes = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_recovery_episode", "episode_no", requireNotNull(result.episodeNo))
                val futures = drafts.map { (d, c) -> pool.submit(Callable { enrollment.complete(d, c, OneShotSupport.context()) }) }
                // 둘째 대기자는 holder가 아니라 첫 대기자(열린 회차 보조 인덱스 레코드)에 막힌다 — 표 전체 대기 수로 확인.
                holder.awaitWaiters("admin_recovery_episode", 2, blockedByHolder = false)
                holder.release()
                futures.map { it.get(60, TimeUnit.SECONDS) }
            }
            pool.shutdown()
            outcomes.all { it is EnrollmentResult.Completed } shouldBe true
            val restored = listOf(a, b).count { AdminRole.SECURITY_ADMIN in storage.load(it.id).activeRoles }
            restored shouldBe 1
            intOf("SELECT COUNT(*) FROM admin_role_event WHERE event_type = 'REACTIVATE'") shouldBe 1
            (openEpisodes() to intOf("SELECT COUNT(*) FROM admin_recovery_episode WHERE closed_at IS NOT NULL")) shouldBe (0 to 1)
        }
        Then("③ a recorded role revoked after the recovery is not restored; ⑤ a code used after the episode expired is refused") {
            // 활성 APPROVER 0명(조건 성립). 회수는 남은 SECURITY_ADMIN(admin)이 한다.
            val admin = storage.securityAdmin()
            val target = withRoles(AdminRole.SECURITY_ADMIN)
            val other = withRoles(AdminRole.SECURITY_ADMIN)
            val result = done(recover(target.loginName.value, other.loginName.value))
            commands.revokeRole(RevokeAdminRole(admin.id.value, target.id.value, "SECURITY_ADMIN", OneShotSupport.context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            OneShotSupport.enroll(enrollment, target.id.value, codeOf(result, target.loginName.value))
            storage.load(target.id).let { it.activeRoles to it.suspendedRoles } shouldBe (emptySet<AdminRole>() to emptySet())
            AdminDbAccounts.root(container).use {
                it.update("UPDATE admin_recovery_episode SET expires_at = opened_at + INTERVAL 1000 MICROSECOND WHERE episode_no = ?", result.episodeNo)
                it.update("UPDATE admin_enrollment_code SET expires_at = issued_at + INTERVAL 1000 MICROSECOND WHERE recovery_episode_no = ?", result.episodeNo)
            }
            enrollment.begin(codeOf(result, other.loginName.value)) shouldBe EnrollmentResult.Failed(EnrollmentFailure.CODE_INVALID)
            // 만료된 열린 회차는 다음 복구 실행 TX가 닫는다.
            done(recover(other.loginName.value)).episodeNo shouldBe result.episodeNo!! + 1
            intOf("SELECT COUNT(*) FROM admin_recovery_episode WHERE episode_no = ? AND closed_at IS NOT NULL", result.episodeNo) shouldBe 1
        }
        Then("an open episode makes a SECURITY_ADMIN-activating enrollment wait on the episode row (activation serialization)") {
            val result = done(recover(pair = pair))
            val sa = AdminDbAccounts.root(container).use { it.scalar("SELECT actor_id FROM admin_account WHERE login_name = 'rec-sa'") as String }
            var draft = (enrollment.begin(codeOf(result, "rec-sa")) as EnrollmentResult.InProgress).draft
            draft = (enrollment.providePassword(draft, "one-shot-password-1".toCharArray()) as EnrollmentResult.InProgress).draft
            val p = enrollment.startTotp(draft) as EnrollmentResult.TotpProvisioning
            val totp = com.paycore.admin.support.TestTotp.code(com.paycore.admin.support.TestTotp.secretOf(p.otpauthUri))
            val pool = Executors.newSingleThreadExecutor()
            val outcome = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_recovery_episode", "episode_no", requireNotNull(result.episodeNo))
                val f = pool.submit(Callable { enrollment.complete(p.draft, totp, OneShotSupport.context()) })
                holder.awaitWaiters("admin_recovery_episode", 1)
                holder.release()
                f.get(60, TimeUnit.SECONDS)
            }
            pool.shutdown()
            outcome shouldBe EnrollmentResult.Completed(sa)
        }
        Then("a SUSPENDED target is refused (recovery is not a suspension lift — fail-closed); nothing changes") {
            val admin = storage.securityAdmin()
            val target = withRoles(AdminRole.APPROVER)
            commands.suspend(com.paycore.admin.account.application.provided.TargetedAccountCommand(admin.id.value, target.id.value, OneShotSupport.context()))
                .shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            recover(target.loginName.value) shouldBe OneShotResult.Refused("TARGET_NOT_ELIGIBLE")
            openEpisodes() shouldBe 0
            recover("no-such-login") shouldBe OneShotResult.Refused("TARGET_NOT_FOUND")
        }
    }

    Given("an open episode whose codes are all unusable (S6 review M-2)") {
        Then("a wrong task-A target whose code was revoked (suspension) leaves an open but dead episode: the next recovery closes it and proceeds") {
            val admin = storage.securityAdmin()
            val wrong = withRoles(AdminRole.VIEWER)
            val right = withRoles(AdminRole.OPERATOR)
            val first = done(recover(wrong.loginName.value))
            commands.suspend(com.paycore.admin.account.application.provided.TargetedAccountCommand(admin.id.value, wrong.id.value, OneShotSupport.context()))
                .shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            val second = done(recover(right.loginName.value))
            second.episodeNo shouldBe first.episodeNo!! + 1
            intOf("SELECT COUNT(*) FROM admin_recovery_episode WHERE episode_no = ? AND closed_at IS NOT NULL", first.episodeNo) shouldBe 1
            openEpisodes() shouldBe 1
        }
        Then("an open episode with a usable code still refuses a new recovery (EPISODE_OPEN)") {
            val target = withRoles(AdminRole.SECURITY_ADMIN)
            done(recover(target.loginName.value))
            recover(pair = pair) shouldBe OneShotResult.Refused("EPISODE_OPEN")
        }
    }

    Given("roles left suspended by an episode are restored only by the normal 2-person ROLE_REACTIVATE citing the episode (S6 review M-3 (a), D-ADMIN1-012)") {
        Then("the OPERATOR and the SECURITY_ADMIN that failed the active-holder condition are reactivated by 2-person approval; a wrong episode or a role not suspended by it is refused") {
            val admin = storage.securityAdmin()
            val target = withRoles(AdminRole.SECURITY_ADMIN, AdminRole.OPERATOR)
            val result = done(recover(target.loginName.value))
            val episodeNo = requireNotNull(result.episodeNo)
            val cause = RecoveryBasis.of(episodeNo)
            OneShotSupport.enroll(enrollment, target.id.value, codeOf(result, target.loginName.value))
            // 활성 SA(admin)가 있어 SA는 자동 재활성 조건 실패, OPERATOR는 기록 밖 → 둘 다 정지.
            storage.load(target.id).let { it.activeRoles to it.suspendedRoles } shouldBe (emptySet<AdminRole>() to setOf(AdminRole.SECURITY_ADMIN, AdminRole.OPERATOR))
            val other = withRoles(AdminRole.VIEWER)
            fun req(on: AdminActorId, why: String) =
                approvals.request(RequestApproval(admin.id.value, storage.load(admin.id).sessionGeneration, "ROLE_REACTIVATE", on.value, null, why, OneShotSupport.context()))
            listOf(req(target.id, RecoveryBasis.of(episodeNo + 99)), req(other.id, cause)) shouldBe
                listOf(ApprovalOutcome.Rejected("NOTHING_TO_REACTIVATE"), ApprovalOutcome.Rejected("NOTHING_TO_REACTIVATE"))
            val id = req(target.id, cause).shouldBeInstanceOf<ApprovalOutcome.Created>().approvalId
            path.approve(id).shouldBeInstanceOf<ApprovalOutcome.Executed>()
            storage.load(target.id).let { it.activeRoles to it.suspendedRoles } shouldBe (setOf(AdminRole.SECURITY_ADMIN, AdminRole.OPERATOR) to emptySet<AdminRole>())
            lastEventOf(target.id, AdminRole.OPERATOR).let { listOf(it.type, it.cause) } shouldBe listOf(RoleEventType.REACTIVATE, cause)
        }
    }

    Given("concurrent recoveries (RecoveryConcurrency) and lock failures (ApprovalLockOrderSpec recovery rows)") {
        Then("S6 review m-1: two runs over the same EXPIRED episode — the second waits on the closing UPDATE and, seeing a different open episode afterwards, is LOST") {
            val target = withRoles(AdminRole.SECURITY_ADMIN)
            val stale = done(recover(target.loginName.value))
            AdminDbAccounts.root(container).use {
                it.update("UPDATE admin_recovery_episode SET expires_at = opened_at + INTERVAL 1000 MICROSECOND WHERE episode_no = ?", stale.episodeNo)
            }
            val other = withRoles(AdminRole.SECURITY_ADMIN)
            val pool = Executors.newFixedThreadPool(2)
            val results = RowLockHolder.open(container).use { holder ->
                // S7 리뷰(결정성): 세 번째 연결이 작업 A 대상 계정 행을 잡는다. TX1은 만료 회차 E1을 닫고(E1 X 잠금) E2를 INSERT한 뒤 대상 행에서 기다린다.
                // 그때 TX2를 시작하면 비잠금 읽기(closed_at IS NULL — 클러스터 행의 이전 버전)로 E1이 아직 열린 것으로 보이고, E1 닫기 UPDATE가 TX1의 E1 잠금에서 기다린다.
                // 놓으면 TX1 커밋 → TX2 UPDATE 0행(READ COMMITTED) → 다시 읽은 열린 회차 = E2 → LOST(INSERT·1062에 도달하지 않는다).
                holder.lock("admin_account", "actor_id", other.id.value)
                val first = pool.submit(Callable { recover(other.loginName.value) })
                holder.awaitWaiters("admin_account", 1)
                val second = pool.submit(Callable { recover(pair = pair) })
                holder.awaitWaiters("admin_recovery_episode", 1, blockedByHolder = false)
                holder.release()
                listOf(first, second).map { it.get(60, TimeUnit.SECONDS) }
            }
            pool.shutdown()
            // Kotest 6.2.5: `(a to b) shouldBe (x to <data object>)`는 data class 필드 비교라 data object 쪽 필드가 없어 늘 통과한다(S7 수정 실측) — 결과는 직접 비교한다.
            results[0].shouldBeInstanceOf<OneShotResult.Done>()
            results[1] shouldBe OneShotResult.Lost
            openEpisodes() shouldBe 1
        }
        Then("two runs that both pass the condition: one commits the episode, the other is LOST (open-episode UNIQUE) with its own audit row") {
            val target = withRoles(AdminRole.SECURITY_ADMIN)
            val pool = Executors.newFixedThreadPool(2)
            val results = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", target.id.value)
                val first = pool.submit(Callable { recover(target.loginName.value) })
                holder.awaitWaiters("admin_account", 1)
                val second = pool.submit(Callable { recover(pair = pair) })
                holder.awaitWaiters("admin_recovery_episode", 1, blockedByHolder = false)
                holder.release()
                listOf(first, second).map { it.get(60, TimeUnit.SECONDS) }
            }
            pool.shutdown()
            // Kotest 6.2.5: `(a to b) shouldBe (x to <data object>)`는 data class 필드 비교라 data object 쪽 필드가 없어 늘 통과한다(S7 수정 실측) — 결과는 직접 비교한다.
            results[0].shouldBeInstanceOf<OneShotResult.Done>()
            results[1] shouldBe OneShotResult.Lost
            intOf("SELECT COUNT(*) FROM admin_recovery_episode") shouldBe 1
            intOf("SELECT COUNT(*) FROM admin_audit_event WHERE action = 'RECOVERY' AND result_code = 'LOST'") shouldBe 1
        }
        Then("1205 (target row held past the timeout) and 1213 (heavier third TX) → RETRY_LATER with no episode, no account change") {
            listOf("1205", "1213").forEach { mode ->
                OneShotSupport.wipe(container)
                val target = withRoles(AdminRole.SECURITY_ADMIN)
                val pool = Executors.newFixedThreadPool(2)
                val outcome = RowLockHolder.open(container).use { holder ->
                    if (mode == "1213") repeat(50) {
                        holder.update("INSERT INTO admin_audit_event (occurred_at, actor_kind, action, result_code, correlation_id) VALUES (UTC_TIMESTAMP(3), 'SYSTEM_JOB', 'TEST_WEIGHT', 'SUCCESS', 'weight')")
                    }
                    holder.lock("admin_account", "actor_id", target.id.value)
                    val f = pool.submit(Callable { recover(target.loginName.value) })
                    if (mode == "1213") {
                        holder.awaitWaiters("admin_account", 1)
                        // 복구 TX는 새 회차(열린 회차 UNIQUE)를 쥔 채 대상 행을 기다린다 → 세 번째 연결이 열린 회차를 INSERT하면 순환 대기.
                        pool.submit(Callable {
                            holder.update("INSERT INTO admin_recovery_episode (episode_no, opened_at, expires_at) VALUES (999, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 DAY)")
                        }).get(60, TimeUnit.SECONDS)
                    }
                    val r = f.get(60, TimeUnit.SECONDS)
                    holder.release()
                    r
                }
                pool.shutdown()
                withClue(mode) { outcome shouldBe OneShotResult.RetryLater }
                (intOf("SELECT COUNT(*) FROM admin_recovery_episode") to storage.load(target.id).status) shouldBe (0 to AccountStatus.ACTIVE)
            }
        }
        Then("the command line maps them to distinct non-zero exit codes") {
            listOf(OneShotResult.RetryLater, OneShotResult.Lost, OneShotResult.Refused("NOT_NEEDED")).map { OneShotExit.of(it).code }.toSet().size shouldBe 3
            OneShotExit.entries.map { it.code }.toSet().size shouldBe OneShotExit.entries.size
            OneShotExit.entries.filter { it != OneShotExit.SUCCESS }.all { it.code != 0 } shouldBe true
        }
    }
})

/** 형식이 맞는 고정 승인 ID(domain 2인 승인 경로 Fixture의 역할 이벤트 근거 — 실제 승인 행 아님). */
private val FIXTURE_APPROVAL_ID = "apr_" + "0".repeat(10) + "FXTVRE" + "0".repeat(10)
