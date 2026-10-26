package com.paycore.admin.account.application

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AuthenticationFailureOutcome
import com.paycore.admin.account.application.provided.AuthenticationFailureOutcome.COUNTED
import com.paycore.admin.account.application.provided.AuthenticationFailureOutcome.LOCKED
import com.paycore.admin.account.application.provided.AuthenticationFailureOutcome.NOT_COUNTED
import com.paycore.admin.account.application.provided.AuthenticationFailureRecorder
import com.paycore.admin.account.application.provided.AuthenticationStep
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.column
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 인증 실패 계수·잠금(D-ADMIN1-005, §4.3.5 — LoginLockout·LoginLockoutConcurrency). 한도 = Demo 5회(application.yml). 독립 로컬 TX, 계정 행 잠금.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class)
class AuthenticationFailureSpec(
    private val failures: AuthenticationFailureRecorder,
    private val storage: AdminAccountStorage,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun context() = AdminRequestContext("corr-" + UUID.randomUUID().toString().take(8), "10.0.0.3")
    fun auditRows(actor: String): List<String> = AdminDbAccounts.root(container).use {
        it.column("SELECT CONCAT(action, ':', result_code, ':', detail) FROM admin_audit_event WHERE target_actor_id = ? ORDER BY event_id", actor)
    }

    Given("an ACTIVE account") {
        Then("failures across password, login TOTP and step-up TOTP are summed; the 5th locks the account, ends its sessions and bumps security_version") {
            val account = storage.save(AdminAccountFixture.enrolledActive())
            val steps = listOf(AuthenticationStep.PASSWORD, AuthenticationStep.LOGIN_TOTP, AuthenticationStep.STEP_UP_TOTP, AuthenticationStep.PASSWORD, AuthenticationStep.LOGIN_TOTP)
            steps.map { failures.recordFailure(account.id.value, it, context()) } shouldBe listOf(COUNTED, COUNTED, COUNTED, COUNTED, LOCKED)
            val locked = storage.load(account.id)
            listOf(locked.status, locked.failedAttempts, locked.sessionGeneration, locked.securityVersion) shouldBe
                listOf(AccountStatus.LOCKED, 5, account.sessionGeneration + 1, account.securityVersion + 1)
            (locked.lockedAt != null) shouldBe true
            auditRows(account.id.value).takeLast(2) shouldBe listOf("AUTHENTICATION_FAILED:LOCKED:step=LOGIN_TOTP;failures=5", "ACCOUNT_LOCKED:SUCCESS:step=LOGIN_TOTP;failures=5")
        }
        Then("a LOCKED account is not counted further (no automatic unlock) and the attempt is still audited") {
            val account = storage.save(AdminAccountFixture.enrolledActive())
            repeat(5) { failures.recordFailure(account.id.value, AuthenticationStep.PASSWORD, context()) }
            val before = storage.load(account.id)
            failures.recordFailure(account.id.value, AuthenticationStep.PASSWORD, context()) shouldBe NOT_COUNTED
            val after = storage.load(account.id)
            listOf(after.status, after.failedAttempts, after.securityVersion) shouldBe listOf(AccountStatus.LOCKED, 5, before.securityVersion)
            auditRows(account.id.value).last() shouldBe "AUTHENTICATION_FAILED:NOT_COUNTED:step=PASSWORD;failures=5"
        }
    }

    Given("accounts that are not ACTIVE or do not exist") {
        Then("PENDING_ENROLLMENT is not counted; an unknown or malformed actor is UNKNOWN_ACCOUNT") {
            val pending = storage.save(AdminAccountFixture.pending())
            listOf(
                failures.recordFailure(pending.id.value, AuthenticationStep.PASSWORD, context()),
                failures.recordFailure(AdminAccountFixture.newActorId().value, AuthenticationStep.PASSWORD, context()),
                failures.recordFailure("nobody", AuthenticationStep.PASSWORD, context()),
            ) shouldBe listOf(NOT_COUNTED, AuthenticationFailureOutcome.UNKNOWN_ACCOUNT, AuthenticationFailureOutcome.UNKNOWN_ACCOUNT)
            storage.load(pending.id).failedAttempts shouldBe 0
        }
    }

    Given("concurrent failures (LoginLockoutConcurrency)") {
        Then("N concurrent failures below the limit are all counted (no lost update)") {
            val account = storage.save(AdminAccountFixture.enrolledActive())
            val outcomes = concurrently(4) { failures.recordFailure(account.id.value, AuthenticationStep.PASSWORD, context()) }
            outcomes shouldBe List(4) { COUNTED }
            storage.load(account.id).failedAttempts shouldBe 4
        }
        Then("10 concurrent failures: exactly 4 counted, exactly one lock, the rest not counted") {
            val account = storage.save(AdminAccountFixture.enrolledActive())
            val outcomes = concurrently(10) { failures.recordFailure(account.id.value, AuthenticationStep.LOGIN_TOTP, context()) }
            listOf(outcomes.count { it == COUNTED }, outcomes.count { it == LOCKED }, outcomes.count { it == NOT_COUNTED }) shouldBe listOf(4, 1, 5)
            val locked = storage.load(account.id)
            listOf(locked.status, locked.failedAttempts, locked.sessionGeneration) shouldBe listOf(AccountStatus.LOCKED, 5, account.sessionGeneration + 1)
        }
    }
})

private fun <T> concurrently(n: Int, block: () -> T): List<T> {
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(n)
    try {
        val futures = List(n) { pool.submit<T> { start.await(); block() } }
        start.countDown()
        return futures.map { it.get(60, TimeUnit.SECONDS) }
    } finally {
        pool.shutdown()
    }
}
