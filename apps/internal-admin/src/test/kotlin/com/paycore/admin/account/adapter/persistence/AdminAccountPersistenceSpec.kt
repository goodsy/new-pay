package com.paycore.admin.account.adapter.persistence

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountFixture.NOW
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminRoleEventStore
import com.paycore.admin.account.application.required.EnrollmentCodeStore
import com.paycore.admin.account.application.required.PersistenceFailure
import com.paycore.admin.account.application.required.StoreConstraint
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.EnrollmentCode
import com.paycore.admin.account.domain.EnrollmentCodeHash
import com.paycore.admin.account.domain.EnrollmentPurpose
import com.paycore.admin.account.domain.LoginName
import com.paycore.admin.account.domain.PendingEnrollment
import com.paycore.admin.account.domain.RoleEventType
import com.paycore.admin.support.AdminDbTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 계정·역할 이벤트·등록 코드 영속 adapter(실제 MySQL 8.4.11, runtime 계정 — 표·열 단위 권한 안에서). Port는 MANDATORY TX라 시험이 TX를 연다.
 */
@SpringBootTest
@AdminDbTest
class AdminAccountPersistenceSpec(
    private val accounts: AdminAccountRepository,
    private val roleEvents: AdminRoleEventStore,
    private val codes: EnrollmentCodeStore,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    fun <T> inTx(block: () -> T): T = tx.execute { block() }
    fun code(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray()).toString().replace("-", "").uppercase()
        .map { c -> if (c in '0'..'1' || c in '8'..'9') 'A' else c }.joinToString("").take(32).padEnd(32, 'Q')

    Given("the account repository") {
        Then("an inserted account round-trips with every field (roles, credentials, counters)") {
            val admin = AdminAccountFixture.withRolesViaApproval(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)
            inTx { accounts.insert(admin) }.version shouldBe 0L
            val loaded = inTx { accounts.find(admin.id) }!!.value
            listOf(
                loaded.loginName, loaded.displayName, loaded.status, loaded.passwordHash, loaded.totp, loaded.lastTotpStep, loaded.activeRoles,
                loaded.suspendedRoles, loaded.sessionGeneration, loaded.securityVersion, loaded.createdAt,
            ) shouldBe listOf(
                admin.loginName, admin.displayName, admin.status, admin.passwordHash, admin.totp, admin.lastTotpStep, admin.activeRoles,
                admin.suspendedRoles, admin.sessionGeneration, admin.securityVersion, admin.createdAt,
            )
            inTx { accounts.findByLoginName(admin.loginName) }!!.value.id shouldBe admin.id
            inTx { accounts.findByLoginName(LoginName("no-such-login")) } shouldBe null
        }

        Then("a locked account is updated through column-level UPDATE privileges and its version increases; a stale version is an optimistic conflict") {
            val account = AdminAccountFixture.pending()
            inTx { accounts.insert(account) }
            val updated = inTx {
                val current = accounts.lock(account.id)!!
                accounts.update(current, AdminAccountFixture.applied(current.value.suspend(AdminAccountFixture.newActorId(), NOW)))
            }
            (updated.version to updated.value.status.name) shouldBe (1L to "SUSPENDED")
            val failure = shouldThrow<PersistenceFailure> {
                inTx {
                    val current = accounts.lock(account.id)!!
                    accounts.update(current.copy(version = 0), current.value)
                }
            }
            failure.shouldBeInstanceOf<PersistenceFailure.OptimisticConflict>()
        }

        Then("a duplicate login name is a business unique conflict (no key value in the failure)") {
            val first = AdminAccountFixture.pending(login = "dup-${UUID.randomUUID().toString().take(8)}")
            inTx { accounts.insert(first) }
            val failure = shouldThrow<PersistenceFailure.UniqueConflict> { inTx { accounts.insert(AdminAccountFixture.pending(login = first.loginName.value)) } }
            failure.constraint shouldBe StoreConstraint.LOGIN_NAME
            (failure.message ?: "").contains(first.loginName.value) shouldBe false
        }

        Then("lockAll locks every requested account and skips unknown IDs") {
            val a = AdminAccountFixture.pending()
            val b = AdminAccountFixture.pending()
            inTx { accounts.insert(a); accounts.insert(b) }
            inTx { accounts.lockAll(listOf(b.id, a.id, AdminAccountFixture.newActorId())) }.keys shouldBe setOf(a.id, b.id)
        }
    }

    Given("the role event store (append-only)") {
        Then("events are appended and read back in order with their cause") {
            val admin = AdminAccountFixture.withRolesViaApproval(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)
            val reset = AdminAccountFixture.applied(admin.beginCredentialReset(EnrollmentPurpose.TOTP_REENROLL, "apr_STORE", AdminAccountFixture.APPROVAL_REQUESTER, AdminAccountFixture.newActorId(), NOW))
            inTx { accounts.insert(admin) }
            val events = (admin.beginCredentialReset(EnrollmentPurpose.TOTP_REENROLL, "apr_STORE", AdminAccountFixture.APPROVAL_REQUESTER, AdminAccountFixture.newActorId(), NOW) as
                com.paycore.admin.account.domain.AccountChange.Applied).events
            inTx { roleEvents.append(events) }
            inTx { roleEvents.history(admin.id) }.map { Triple(it.role, it.type, it.cause) } shouldBe
                events.map { Triple(it.role, RoleEventType.SUSPEND, "apr_STORE") }
            reset.suspendedRoles shouldBe setOf(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)
        }
    }

    Given("the enrollment code store") {
        val target = AdminAccountFixture.pending()
        inTx { accounts.insert(target) }
        fun issue(seed: String, expiresIn: Long = 3600): EnrollmentCode {
            val c = EnrollmentCode.issue(EnrollmentCodeHash.of(code(seed)), target.id, PendingEnrollment(EnrollmentPurpose.ENROLL, "enc_${seed.replace('-', '_')}".take(40)), NOW, NOW.plusSeconds(expiresIn))
            inTx { codes.insert(c) }
            return c
        }

        Then("a RECOVERY code with episode and recorded roles round-trips") {
            val recovery = EnrollmentCode(EnrollmentCodeHash.of(code("recovery")), target.id, EnrollmentPurpose.RECOVERY, "episode:3", 3, setOf(AdminRole.APPROVER), NOW, NOW.plusSeconds(60), null, null)
            inTx { codes.insert(recovery) }
            inTx { codes.find(recovery.hash) } shouldBe recovery
        }

        Then("consume succeeds exactly once; expired or revoked codes are not consumed") {
            val usable = issue("usable")
            val expired = issue("expired", expiresIn = 10)
            listOf(
                inTx { codes.consume(usable.hash, NOW) }, inTx { codes.consume(usable.hash, NOW) }, inTx { codes.consume(expired.hash, NOW.plusSeconds(10)) },
            ) shouldBe listOf(true, false, false)
            inTx { codes.find(usable.hash) }!!.usedAt shouldBe NOW
        }

        Then("revokeUnused revokes only the target's unused, unrevoked codes; a revoked code cannot be consumed") {
            val other = AdminAccountFixture.pending()
            inTx { accounts.insert(other) }
            val c1 = issue("revoke-1")
            val c2 = issue("revoke-2")
            inTx { codes.consume(c2.hash, NOW) }
            val foreign = EnrollmentCode.issue(EnrollmentCodeHash.of(code("foreign")), other.id, PendingEnrollment(EnrollmentPurpose.ENROLL, "enc_f"), NOW, NOW.plusSeconds(60))
            inTx { codes.insert(foreign) }
            val revoked = inTx { codes.revokeUnused(target.id, NOW) }
            (c1.basis in revoked && c2.basis !in revoked && foreign.basis !in revoked) shouldBe true
            listOf(inTx { codes.find(c1.hash) }!!.revokedAt, inTx { codes.find(c2.hash) }!!.revokedAt, inTx { codes.find(foreign.hash) }!!.revokedAt) shouldBe
                listOf(NOW, null, null)
            inTx { codes.consume(c1.hash, NOW) } shouldBe false
        }

        Then("two transactions consuming the same code concurrently: exactly one wins (atomic UPDATE)") {
            val contested = issue("contested")
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val results = List(2) { pool.submit<Boolean> { start.await(); inTx { codes.consume(contested.hash, NOW) } } }
            start.countDown()
            val outcomes = results.map { it.get(20, TimeUnit.SECONDS) }
            pool.shutdown()
            outcomes.sorted() shouldBe listOf(false, true)
        }
    }
})
