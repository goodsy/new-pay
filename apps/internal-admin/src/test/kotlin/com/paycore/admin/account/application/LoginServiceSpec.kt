package com.paycore.admin.account.application

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.AdminLoginUseCase
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AuthenticationFailureRecorder
import com.paycore.admin.account.application.provided.AuthenticationStep
import com.paycore.admin.account.application.provided.PasswordStepResult
import com.paycore.admin.account.application.provided.PasswordVerified
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import com.paycore.admin.account.application.provided.TotpStepResult
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.audit.domain.AuditActorKind
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.CountingAuditStore
import com.paycore.admin.support.CountingTransactionManager
import com.paycore.admin.support.LoginProbeConfig
import com.paycore.admin.support.ProbePasswordHasher
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 2단계 로그인 Use Case(§4.3.2·§4.3.5 — LoginPathEquivalence·LoginCredentialChangeRace·LoginLockout·NewLoginWins·SingleSessionRace). 실제 MySQL·실제 암호 adapter.
 * 경로 동등성은 처리 시간이 아니라 **호출 수**로 고정한다: 분기마다 BCrypt 비교 1회·새 로컬 TX 1회·감사 append 1회, 결과는 성공 외 모두 같은 값.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, LoginProbeConfig::class)
class LoginServiceSpec(
    private val login: AdminLoginUseCase,
    private val commands: AdminAccountCommandUseCase,
    private val failures: AuthenticationFailureRecorder,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val hasher: ProbePasswordHasher,
    private val audits: CountingAuditStore,
    transactionManager: PlatformTransactionManager,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val transactions = transactionManager as CountingTransactionManager
    fun context() = AdminRequestContext("corr-" + UUID.randomUUID().toString().take(8), "10.0.0.7")
    fun auditOf(actorOrTarget: String): List<String> = AdminDbAccounts.root(container).use {
        it.column(
            "SELECT CONCAT(actor_kind, ':', action, ':', result_code, ':', COALESCE(detail, '-')) FROM admin_audit_event " +
                "WHERE target_actor_id = ? AND action LIKE 'LOGIN%' ORDER BY event_id",
            actorOrTarget,
        )
    }

    /** 한 번의 비밀번호 단계를 계측한다: (결과, BCrypt 비교 수, 새 TX 수, 감사 append 수). */
    fun probe(loginName: String, password: String): List<Any> {
        hasher.matchCalls.set(0)
        transactions.begins.set(0)
        audits.appends.set(0)
        val result = login.verifyPassword(loginName, password.toCharArray(), context())
        return listOf(result, hasher.matchCalls.get(), transactions.begins.get(), audits.appends.get())
    }

    Given("the password step (LoginPathEquivalence — m-4)") {
        Then("success, wrong password, unknown, LOCKED, SUSPENDED, PENDING and limit-reaching each make exactly 1 BCrypt compare, 1 local TX and 1 audit row") {
            val active = ready.withoutRoles()
            val wrong = ready.withoutRoles()
            val locked = ready.withoutRoles().also { a -> repeat(5) { failures.recordFailure(a.account.id.value, AuthenticationStep.PASSWORD, context()) } }
            val suspended = ready.withoutRoles().also { a ->
                val admin = storage.securityAdmin()
                commands.suspend(TargetedAccountCommand(admin.id.value, a.account.id.value, context())) shouldBe AccountCommandResult.Succeeded(a.account.id.value)
            }
            val pending = storage.save(AdminAccountFixture.pending())
            val atLimit = ready.withoutRoles().also { a -> repeat(4) { failures.recordFailure(a.account.id.value, AuthenticationStep.PASSWORD, context()) } }
            val success = probe(active.loginName, active.password)
            success.drop(1) shouldBe listOf(1, 1, 1)
            success.first().shouldBeInstanceOf<PasswordStepResult.Verified>().verified.actorId shouldBe active.account.id.value
            listOf(
                probe(wrong.loginName, "not-the-password"),
                probe("no-such-login-" + UUID.randomUUID().toString().take(6), "whatever-password"),
                probe("NOT A VALID LOGIN", "whatever-password"),
                probe(locked.loginName, locked.password),
                probe(suspended.loginName, suspended.password),
                probe(pending.loginName.value, "whatever-password"),
                probe(atLimit.loginName, "not-the-password"),
            ) shouldBe List(7) { listOf(PasswordStepResult.InvalidCredentials, 1, 1, 1) }
            storage.load(atLimit.account.id).status shouldBe AccountStatus.LOCKED
        }
        Then("a password over the byte limit (73 UTF-8 bytes) takes the same path: 1 dummy compare, 1 TX, 1 audit row, counted as a wrong password (S4 review m-3)") {
            val account = ready.withoutRoles()
            probe(account.loginName, "a".repeat(73)) shouldBe listOf(PasswordStepResult.InvalidCredentials, 1, 1, 1)
            storage.load(account.account.id).failedAttempts shouldBe 1
            auditOf(account.account.id.value).last() shouldBe "ADMIN:LOGIN_PASSWORD:FAILED:failures=1"
        }
        Then("BCrypt's 72-byte truncation cannot be used: a 73-byte input whose first 72 bytes are the real password is refused (S4 review m-3)") {
            val account = ready.withoutRoles(password = "b".repeat(72))
            login.verifyPassword(account.loginName, "b".repeat(72).toCharArray(), context()).shouldBeInstanceOf<PasswordStepResult.Verified>()
            login.verifyPassword(account.loginName, ("b".repeat(72) + "x").toCharArray(), context()) shouldBe PasswordStepResult.InvalidCredentials
        }
        Then("an ACTIVE account already at the failure limit (limit lowered by configuration) is refused before the BCrypt result is applied, without counting (S4 review m-4)") {
            val account = ready.withoutRoles()
            AdminDbAccounts.root(container).use { it.update("UPDATE admin_account SET failed_attempts = 5 WHERE actor_id = ?", account.account.id.value) }
            probe(account.loginName, account.password) shouldBe listOf(PasswordStepResult.InvalidCredentials, 1, 1, 1)
            storage.load(account.account.id).let { it.failedAttempts to it.status } shouldBe (5 to AccountStatus.ACTIVE)
            auditOf(account.account.id.value).last() shouldBe "ADMIN:LOGIN_PASSWORD:NOT_COUNTED:reason=LIMIT"
        }
        Then("an unknown login is audited as ANONYMOUS with only the keyed HMAC of the login name (n-2)") {
            val name = "ghost-" + UUID.randomUUID().toString().take(6)
            probe(name, "whatever-password")
            val event = audits.last.get()!!
            listOf(event.actorKind, event.actorId, event.action, event.resultCode, event.loginNameHmac?.size, event.detail?.contains(name)) shouldBe
                listOf(AuditActorKind.ANONYMOUS, null, "LOGIN_PASSWORD", "FAILED", 32, false)
        }
    }

    Given("a credential change between the BCrypt compare and the locked TX (LoginCredentialChangeRace — rev 0.3 m-D)") {
        Then("the result is not applied: generic failure, no count, no session; audited as STALE") {
            listOf(true, false).forEach { rightPassword ->
                val account = ready.withoutRoles()
                hasher.duringMatch.set {
                    AdminDbAccounts.root(container).use {
                        it.update("UPDATE admin_account SET security_version = security_version + 1, version = version + 1 WHERE actor_id = ?", account.account.id.value)
                    }
                }
                val result = login.verifyPassword(account.loginName, (if (rightPassword) account.password else "not-the-password").toCharArray(), context())
                result shouldBe PasswordStepResult.InvalidCredentials
                storage.load(account.account.id).failedAttempts shouldBe 0
                auditOf(account.account.id.value).last() shouldBe "ADMIN:LOGIN_PASSWORD:REJECTED:reason=STALE"
            }
        }
    }

    Given("the TOTP step") {
        fun verified(account: LoginReadyAccounts.LoginReady): PasswordVerified =
            (login.verifyPassword(account.loginName, account.password.toCharArray(), context()) as PasswordStepResult.Verified).verified

        Then("a correct code completes the login: failures reset, step recorded, session generation +1 (new login wins)") {
            val account = ready.withoutRoles()
            failures.recordFailure(account.account.id.value, AuthenticationStep.PASSWORD, context())
            val before = storage.load(account.account.id)
            val result = login.verifyTotp(verified(account), TestTotp.code(account.secret), context()).shouldBeInstanceOf<TotpStepResult.Authenticated>()
            val after = storage.load(account.account.id)
            listOf(after.failedAttempts, after.sessionGeneration, result.sessionGeneration, after.securityVersion) shouldBe
                listOf(0, before.sessionGeneration + 1, before.sessionGeneration + 1, before.securityVersion)
            auditOf(account.account.id.value).last() shouldBe "ADMIN:LOGIN_SUCCEEDED:SUCCESS:-"
        }
        Then("a reused step, a wrong code or a stale password step is rejected; the code reuse and wrong code count, the stale step ends the session") {
            val account = ready.withoutRoles()
            val first = verified(account)
            login.verifyTotp(first, TestTotp.code(account.secret), context()).shouldBeInstanceOf<TotpStepResult.Authenticated>()
            login.verifyTotp(verified(account), TestTotp.code(account.secret), context()) shouldBe TotpStepResult.Rejected(endSession = false)
            val code = TestTotp.code(account.secret, Instant.now().plusSeconds(30))
            val wrong = ((code.toInt() + 500_000) % 1_000_000).toString().padStart(6, '0')
            login.verifyTotp(verified(account), wrong, context()) shouldBe TotpStepResult.Rejected(endSession = false)
            storage.load(account.account.id).failedAttempts shouldBe 2
            val stale = first.copy(securityVersion = first.securityVersion - 1)
            login.verifyTotp(stale, code, context()) shouldBe TotpStepResult.Rejected(endSession = true)
            storage.load(account.account.id).failedAttempts shouldBe 2
        }
        Then("an expired password step or a malformed code is rejected with one REJECTED audit row and no count (S4 review nit)") {
            val account = ready.withoutRoles()
            val fresh = verified(account)
            login.verifyTotp(fresh.copy(stepExpiresAt = Instant.now().minusSeconds(1)), TestTotp.code(account.secret), context()) shouldBe TotpStepResult.Rejected(endSession = true)
            login.verifyTotp(fresh, "12ab56", context()) shouldBe TotpStepResult.Rejected(endSession = false)
            storage.load(account.account.id).failedAttempts shouldBe 0
            auditOf(account.account.id.value).takeLast(2) shouldBe listOf("ADMIN:LOGIN_TOTP:REJECTED:reason=STEP_EXPIRED", "ADMIN:LOGIN_TOTP:REJECTED:reason=FORMAT")
        }
        Then("password and TOTP failures are summed: the 5th failure locks and ends the session; a later correct login is refused") {
            val account = ready.withoutRoles()
            repeat(4) { login.verifyPassword(account.loginName, "not-the-password".toCharArray(), context()) }
            val code = TestTotp.code(account.secret)
            val wrong = ((code.toInt() + 500_000) % 1_000_000).toString().padStart(6, '0')
            login.verifyTotp(verified(account), wrong, context()) shouldBe TotpStepResult.Rejected(endSession = true)
            storage.load(account.account.id).status shouldBe AccountStatus.LOCKED
            login.verifyPassword(account.loginName, account.password.toCharArray(), context()) shouldBe PasswordStepResult.InvalidCredentials
        }
        Then("two TOTP steps of two sessions racing: generations are serialized by the row lock and only the last one matches the row (SingleSessionRace)") {
            val account = ready.bootstrapped(AdminRole.APPROVER)
            val sessions = listOf(verified(account), verified(account))
            val codes = listOf(TestTotp.code(account.secret), TestTotp.code(account.secret, Instant.now().plusSeconds(30)))
            val pool = Executors.newFixedThreadPool(2)
            val results = sessions.indices.map { i -> pool.submit<TotpStepResult> { login.verifyTotp(sessions[i], codes[i], context()) } }
                .map { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()
            val generations = results.filterIsInstance<TotpStepResult.Authenticated>().map { it.sessionGeneration }
            (generations.isNotEmpty() && generations.toSet().size == generations.size) shouldBe true
            storage.load(account.account.id).sessionGeneration shouldBe generations.max()
        }
    }
})
