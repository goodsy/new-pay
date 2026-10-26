package com.paycore.admin.account.application

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.CreateAdminAccount
import com.paycore.admin.account.application.provided.EnrollmentDraft
import com.paycore.admin.account.application.provided.EnrollmentFailure
import com.paycore.admin.account.application.provided.EnrollmentResult
import com.paycore.admin.account.application.provided.EnrollmentUseCase
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import com.paycore.admin.account.application.required.CipherContext
import com.paycore.admin.account.application.required.EnrollmentCodeStore
import com.paycore.admin.account.application.required.PasswordHasher
import com.paycore.admin.account.application.required.SealedSecret
import com.paycore.admin.account.application.required.SecretUnsealFailure
import com.paycore.admin.account.application.required.TotpSecretCipher
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.EnrollmentCode
import com.paycore.admin.account.domain.EnrollmentCodeHash
import com.paycore.admin.account.domain.EnrollmentPurpose
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.RowLockHolder
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 등록 코드 흐름(§4.3.6 — EnrollmentPurposeFlow·EnrollmentPendingValue·EnrollmentCode·EnrollmentCodeRace·SuspendRevokesEnrollment 묶음). 실제 MySQL·실제 암호 adapter.
 * TOTP 확인 코드는 시험 쪽 독립 계산([TestTotp])으로 만든다.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class EnrollmentFlowSpec(
    private val enrollment: EnrollmentUseCase,
    private val commands: AdminAccountCommandUseCase,
    private val storage: AdminAccountStorage,
    private val approvalPath: ApprovalPath,
    private val codes: EnrollmentCodeStore,
    private val passwordHasher: PasswordHasher,
    private val cipher: TotpSecretCipher,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val password = "correct-horse-battery"
    fun context() = AdminRequestContext("corr-" + UUID.randomUUID().toString().take(8), "10.0.0.2")
    fun newAccountWithCode(): Pair<AdminActorId, String> {
        val admin = storage.securityAdmin()
        val created = commands.createAccount(CreateAdminAccount(admin.id.value, "en-" + UUID.randomUUID().toString().take(8), "등록자", context()))
            as AccountCommandResult.Succeeded
        return AdminActorId(created.targetActorId) to created.enrollmentCode!!
    }
    fun begin(code: String): EnrollmentDraft = enrollment.begin(code).shouldBeInstanceOf<EnrollmentResult.InProgress>().draft
    fun withPassword(draft: EnrollmentDraft, raw: String = password): EnrollmentDraft =
        enrollment.providePassword(draft, raw.toCharArray()).shouldBeInstanceOf<EnrollmentResult.InProgress>().draft
    fun withTotp(draft: EnrollmentDraft): Pair<EnrollmentDraft, ByteArray> =
        enrollment.startTotp(draft).shouldBeInstanceOf<EnrollmentResult.TotpProvisioning>().let { it.draft to TestTotp.secretOf(it.otpauthUri) }
    fun storedSecret(account: AdminAccount): ByteArray =
        cipher.decrypt(SealedSecret(account.totp!!.keyVersion, account.totp.ciphertext), CipherContext(CipherContext.STORED_TOTP, listOf(account.id.value)))
    fun completedRows(id: AdminActorId): List<String> = AdminDbAccounts.root(container).use {
        it.column(
            "SELECT CONCAT(actor_kind, ':', action, ':', result_code, ':', detail) FROM admin_audit_event WHERE target_actor_id = ? AND action = 'ENROLLMENT_COMPLETED' ORDER BY event_id",
            id.value,
        )
    }

    Given("ENROLL (password + TOTP)") {
        Then("the full flow activates the account with exactly the session's password and TOTP secret, consumes the code and audits the completion") {
            val (id, code) = newAccountWithCode()
            val (draft, secret) = withTotp(withPassword(begin(code)))
            enrollment.complete(draft, TestTotp.code(secret), context()) shouldBe EnrollmentResult.Completed(id.value)
            val account = storage.load(id)
            listOf(account.status, account.pending, account.failedAttempts) shouldBe listOf(AccountStatus.ACTIVE, null, 0)
            passwordHasher.matches(password.toCharArray(), account.passwordHash) shouldBe true
            storedSecret(account).contentEquals(secret) shouldBe true
            storage.code(EnrollmentCodeHash.of(code))!!.usedAt shouldNotBe null
            completedRows(id) shouldBe listOf("ADMIN:ENROLLMENT_COMPLETED:SUCCESS:purpose=ENROLL")
        }
        Then("the otpauth URI carries the issuer, the login name and SHA1/6/30 parameters") {
            val (_, code) = newAccountWithCode()
            val uri = enrollment.startTotp(withPassword(begin(code))).shouldBeInstanceOf<EnrollmentResult.TotpProvisioning>().otpauthUri
            Regex("^otpauth://totp/PayCore%20Admin%20%28Demo%29:en-[0-9a-f]{8}\\?secret=[A-Z2-7]{32}&issuer=PayCore%20Admin%20%28Demo%29&algorithm=SHA1&digits=6&period=30$")
                .matches(uri) shouldBe true
        }
    }

    Given("pending values (rev 0.4 n-1)") {
        Then("before completion the account row is unchanged and the sealed values open only in their own progress/use context") {
            val (id, code) = newAccountWithCode()
            val before = storage.load(id)
            val (draft, _) = withTotp(withPassword(begin(code)))
            val after = storage.load(id)
            listOf(after.passwordHash, after.totp, after.securityVersion, after.status) shouldBe listOf(null, null, before.securityVersion, AccountStatus.PENDING_ENROLLMENT)
            val sealed = draft.pendingTotpSecret!!.let { SealedSecret(it.keyVersion, it.bytes) }
            val own = CipherContext(CipherContext.ENROLLMENT_PENDING, listOf(draft.progressId, draft.targetActorId, draft.purpose, "totp-secret"))
            cipher.decrypt(sealed, own).size shouldBe 20
            listOf(
                own.copy(parts = listOf("other-progress", draft.targetActorId, draft.purpose, "totp-secret")),
                own.copy(parts = listOf(draft.progressId, draft.targetActorId, draft.purpose, "password-hash")),
                CipherContext(CipherContext.STORED_TOTP, listOf(draft.targetActorId)),
            ).forEach { shouldThrow<SecretUnsealFailure> { cipher.decrypt(sealed, it) } }
        }
        Then("a draft whose sealed values were swapped between uses fails the step without consuming the code") {
            val (id, code) = newAccountWithCode()
            val (draft, secret) = withTotp(withPassword(begin(code)))
            val swapped = draft.with(pendingPasswordHash = draft.pendingTotpSecret, pendingTotpSecret = draft.pendingPasswordHash)
            enrollment.complete(swapped, TestTotp.code(secret), context()) shouldBe EnrollmentResult.Failed(EnrollmentFailure.STEP_INVALID)
            storage.code(EnrollmentCodeHash.of(code))!!.usedAt shouldBe null
            storage.load(id).status shouldBe AccountStatus.PENDING_ENROLLMENT
        }
    }

    Given("purpose-specific elements (D-ADMIN1-011, EnrollmentPurposeFlow)") {
        Then("PASSWORD_RESET sets only the password: TOTP ciphertext and key version stay; suspended roles stay suspended; a TOTP step is refused") {
            val (pending, code) = approvalPath.credentialReset("PASSWORD_RESET", storage.bootstrapped(AdminRole.APPROVER).id)
            val draft = begin(code)
            enrollment.startTotp(draft) shouldBe EnrollmentResult.Failed(EnrollmentFailure.STEP_INVALID)
            enrollment.complete(withPassword(draft, "new-password-123"), null, context()) shouldBe EnrollmentResult.Completed(pending.id.value)
            val account = storage.load(pending.id)
            listOf(account.status, account.totp, account.activeRoles, account.suspendedRoles) shouldBe
                listOf(AccountStatus.ACTIVE, AdminAccountFixture.TOTP, emptySet<AdminRole>(), setOf(AdminRole.APPROVER))
            passwordHasher.matches("new-password-123".toCharArray(), account.passwordHash) shouldBe true
        }
        Then("TOTP_REENROLL sets only the TOTP: the password hash stays; a password step is refused") {
            val (pending, code) = approvalPath.credentialReset("TOTP_REENROLL", approvalPath.withRolesViaApproval(AdminRole.OPERATOR).id)
            val draft = begin(code)
            enrollment.providePassword(draft, password.toCharArray()) shouldBe EnrollmentResult.Failed(EnrollmentFailure.STEP_INVALID)
            val (withSecret, secret) = withTotp(draft)
            enrollment.complete(withSecret, TestTotp.code(secret), context()) shouldBe EnrollmentResult.Completed(pending.id.value)
            val account = storage.load(pending.id)
            (account.status to account.passwordHash) shouldBe (AccountStatus.ACTIVE to AdminAccountFixture.PASSWORD_HASH)
            storedSecret(account).contentEquals(secret) shouldBe true
        }
        Then("completing without a required element, or with a TOTP code the purpose does not take, fails the step") {
            val (_, code) = newAccountWithCode()
            val onlyPassword = withPassword(begin(code))
            enrollment.complete(onlyPassword, "123456", context()) shouldBe EnrollmentResult.Failed(EnrollmentFailure.STEP_INVALID)
            val (reset, resetCode) = approvalPath.credentialReset("PASSWORD_RESET", approvalPath.withRolesViaApproval(AdminRole.VIEWER).id)
            enrollment.complete(withPassword(begin(resetCode), "new-password-456"), "123456", context()) shouldBe EnrollmentResult.Failed(EnrollmentFailure.STEP_INVALID)
            storage.load(reset.id).status shouldBe AccountStatus.PENDING_ENROLLMENT
        }
        Then("password length bounds: minimum in Unicode code points (a surrogate pair is one), maximum in UTF-8 bytes") {
            val (_, code) = newAccountWithCode()
            val draft = begin(code)
            listOf(
                enrollment.providePassword(draft, "short-11ch!".toCharArray()),
                enrollment.providePassword(draft, "가".repeat(25).toCharArray()),
                enrollment.providePassword(draft, "😀".repeat(11).toCharArray()),
            ) shouldBe listOf(
                EnrollmentResult.Failed(EnrollmentFailure.PASSWORD_TOO_SHORT), EnrollmentResult.Failed(EnrollmentFailure.PASSWORD_TOO_LONG),
                EnrollmentResult.Failed(EnrollmentFailure.PASSWORD_TOO_SHORT),
            )
            enrollment.providePassword(draft, "가".repeat(24).toCharArray()).shouldBeInstanceOf<EnrollmentResult.InProgress>()
        }
    }

    Given("code validity (EnrollmentCode)") {
        Then("malformed, unknown, expired and reissued (revoked) codes are the same CODE_INVALID") {
            val (id, code) = newAccountWithCode()
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val expired = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            storage.inTx {
                codes.insert(EnrollmentCode.issue(EnrollmentCodeHash.of(expired), id, storage.load(id).pending!!, now.minusSeconds(120), now.minusSeconds(60)))
            }
            val admin = storage.securityAdmin()
            commands.reissueEnrollCode(TargetedAccountCommand(admin.id.value, id.value, context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            listOf("not-a-code", "A".repeat(32), expired, code).map { enrollment.begin(it) } shouldBe List(4) { EnrollmentResult.Failed(EnrollmentFailure.CODE_INVALID) }
        }
        Then("a code that expires between begin and completion is not consumed: the completion TX judges expiry with the server clock and rolls back") {
            val (id, original) = newAccountWithCode()
            val shortLived = "MNOPQRSTUVWXYZ234567ABCDEFGHIJKL"
            val issuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            storage.inTx {
                codes.revokeUnused(id, issuedAt)
                codes.insert(EnrollmentCode.issue(EnrollmentCodeHash.of(shortLived), id, storage.load(id).pending!!, issuedAt, issuedAt.plusSeconds(3)))
            }
            val (draft, secret) = withTotp(withPassword(begin(shortLived)))
            while (!Instant.now().isAfter(issuedAt.plusSeconds(3))) Thread.sleep(200)
            enrollment.complete(draft, TestTotp.code(secret), context()) shouldBe EnrollmentResult.Failed(EnrollmentFailure.CODE_INVALID)
            completedRows(id) shouldBe emptyList()
            val account = storage.load(id)
            listOf(account.status, account.passwordHash, storage.code(EnrollmentCodeHash.of(shortLived))!!.usedAt) shouldBe listOf(AccountStatus.PENDING_ENROLLMENT, null, null)
            enrollment.begin(original) shouldBe EnrollmentResult.Failed(EnrollmentFailure.CODE_INVALID)
        }
        Then("a draft begun before a reissue cannot complete with the old code (revoked + basis replaced)") {
            val (id, code) = newAccountWithCode()
            val (draft, secret) = withTotp(withPassword(begin(code)))
            val admin = storage.securityAdmin()
            commands.reissueEnrollCode(TargetedAccountCommand(admin.id.value, id.value, context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            enrollment.complete(draft, TestTotp.code(secret), context()) shouldBe EnrollmentResult.Failed(EnrollmentFailure.NOT_APPLICABLE)
            storage.load(id).status shouldBe AccountStatus.PENDING_ENROLLMENT
            completedRows(id) shouldBe listOf("ANONYMOUS:ENROLLMENT_COMPLETED:REJECTED:reason=NOT_APPLICABLE")
        }
        Then("an expired draft (step validity) fails the step") {
            val (_, code) = newAccountWithCode()
            val draft = begin(code)
            val stale = EnrollmentDraft(draft.progressId, draft.codeHash, draft.targetActorId, draft.purpose, draft.basis, Instant.now().minusSeconds(1))
            enrollment.providePassword(stale, password.toCharArray()) shouldBe EnrollmentResult.Failed(EnrollmentFailure.STEP_INVALID)
        }
    }

    Given("TOTP confirmation and single use") {
        Then("a wrong TOTP code fails the step; the code is not consumed and a correct retry succeeds") {
            val (id, code) = newAccountWithCode()
            val (draft, secret) = withTotp(withPassword(begin(code)))
            val right = TestTotp.code(secret)
            val wrong = ((right.toInt() + 500_000) % 1_000_000).toString().padStart(6, '0')
            enrollment.complete(draft, wrong, context()) shouldBe EnrollmentResult.Failed(EnrollmentFailure.STEP_INVALID)
            storage.code(EnrollmentCodeHash.of(code))!!.usedAt shouldBe null
            storage.load(id).status shouldBe AccountStatus.PENDING_ENROLLMENT
            enrollment.complete(draft, right, context()) shouldBe EnrollmentResult.Completed(id.value)
        }
        Then("completing twice with the same draft: the second attempt is CODE_INVALID (used code — metrics only, no audit) and changes nothing") {
            val (id, code) = newAccountWithCode()
            val (draft, secret) = withTotp(withPassword(begin(code)))
            enrollment.complete(draft, TestTotp.code(secret), context()) shouldBe EnrollmentResult.Completed(id.value)
            val first = storage.load(id)
            enrollment.complete(draft, TestTotp.code(secret), context()) shouldBe EnrollmentResult.Failed(EnrollmentFailure.CODE_INVALID)
            storage.load(id).securityVersion shouldBe first.securityVersion
            completedRows(id) shouldBe listOf("ADMIN:ENROLLMENT_COMPLETED:SUCCESS:purpose=ENROLL")
        }
        Then("two sessions completing the same code, both proven to wait on the account row lock: exactly one wins with only its own password and secret (EnrollmentCodeRace)") {
            val (id, code) = newAccountWithCode()
            val sessions = listOf("winner-or-loser-1", "winner-or-loser-2").map { pw ->
                val (draft, secret) = withTotp(withPassword(begin(code), pw))
                Triple(pw, draft, secret)
            }
            val pool = Executors.newFixedThreadPool(2)
            val results = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", id.value)
                val futures = sessions.map { (_, draft, secret) -> pool.submit<EnrollmentResult> { enrollment.complete(draft, TestTotp.code(secret), context()) } }
                holder.awaitWaiters("admin_account", 2)
                holder.release()
                futures.map { it.get(30, TimeUnit.SECONDS) }
            }
            pool.shutdown()
            results.sortedBy { it is EnrollmentResult.Completed }.first() shouldBe EnrollmentResult.Failed(EnrollmentFailure.CODE_INVALID)
            results.count { it is EnrollmentResult.Completed } shouldBe 1
            val (winnerPassword, _, winnerSecret) = sessions[results.indexOfFirst { it is EnrollmentResult.Completed }]
            val (loserPassword, _, loserSecret) = sessions[results.indexOfFirst { it !is EnrollmentResult.Completed }]
            val account = storage.load(id)
            passwordHasher.matches(winnerPassword.toCharArray(), account.passwordHash) shouldBe true
            passwordHasher.matches(loserPassword.toCharArray(), account.passwordHash) shouldBe false
            (storedSecret(account).contentEquals(winnerSecret) && !storedSecret(account).contentEquals(loserSecret)) shouldBe true
        }
    }

    Given("suspension versus completion (H-B, SuspendRevokesEnrollment)") {
        Then("after suspension the code is revoked and completion is NOT_APPLICABLE, audited in a separate TX; the account stays SUSPENDED") {
            val (id, code) = newAccountWithCode()
            val (draft, secret) = withTotp(withPassword(begin(code)))
            val admin = storage.securityAdmin()
            commands.suspend(TargetedAccountCommand(admin.id.value, id.value, context())) shouldBe AccountCommandResult.Succeeded(id.value)
            enrollment.complete(draft, TestTotp.code(secret), context()) shouldBe EnrollmentResult.Failed(EnrollmentFailure.NOT_APPLICABLE)
            storage.load(id).status shouldBe AccountStatus.SUSPENDED
            completedRows(id) shouldBe listOf("ANONYMOUS:ENROLLMENT_COMPLETED:REJECTED:reason=NOT_APPLICABLE")
            enrollment.begin(code) shouldBe EnrollmentResult.Failed(EnrollmentFailure.CODE_INVALID)
        }
        Then("suspension first, forced: suspension holds the account row while waiting on the code row, completion waits on the account row → completion NOT_APPLICABLE, code revoked") {
            val (id, code) = newAccountWithCode()
            val (draft, secret) = withTotp(withPassword(begin(code)))
            val admin = storage.securityAdmin()
            val pool = Executors.newFixedThreadPool(2)
            val (suspended, completed) = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_enrollment_code", "code_hash", EnrollmentCodeHash.of(code).bytes)
                val suspension = pool.submit<AccountCommandResult> { commands.suspend(TargetedAccountCommand(admin.id.value, id.value, context())) }
                holder.awaitWaiters("admin_enrollment_code", 1)
                val completion = pool.submit<EnrollmentResult> { enrollment.complete(draft, TestTotp.code(secret), context()) }
                holder.awaitWaiters("admin_account", 1, blockedByHolder = false)
                holder.release()
                suspension.get(30, TimeUnit.SECONDS) to completion.get(30, TimeUnit.SECONDS)
            }
            pool.shutdown()
            (suspended to completed) shouldBe (AccountCommandResult.Succeeded(id.value) to EnrollmentResult.Failed(EnrollmentFailure.NOT_APPLICABLE))
            val account = storage.load(id)
            val row = storage.code(EnrollmentCodeHash.of(code))!!
            listOf(account.status, account.statusBeforeSuspend, account.passwordHash, row.usedAt == null, row.revokedAt != null) shouldBe
                listOf(AccountStatus.SUSPENDED, AccountStatus.PENDING_ENROLLMENT, null, true, true)
        }
        Then("completion first, forced: completion holds the account row while waiting on the code row, suspension waits on the account row → ACTIVE account suspended, code used") {
            val (id, code) = newAccountWithCode()
            val (draft, secret) = withTotp(withPassword(begin(code)))
            val admin = storage.securityAdmin()
            val pool = Executors.newFixedThreadPool(2)
            val (completed, suspended) = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_enrollment_code", "code_hash", EnrollmentCodeHash.of(code).bytes)
                val completion = pool.submit<EnrollmentResult> { enrollment.complete(draft, TestTotp.code(secret), context()) }
                holder.awaitWaiters("admin_enrollment_code", 1)
                val suspension = pool.submit<AccountCommandResult> { commands.suspend(TargetedAccountCommand(admin.id.value, id.value, context())) }
                holder.awaitWaiters("admin_account", 1, blockedByHolder = false)
                holder.release()
                completion.get(30, TimeUnit.SECONDS) to suspension.get(30, TimeUnit.SECONDS)
            }
            pool.shutdown()
            (completed to suspended) shouldBe (EnrollmentResult.Completed(id.value) to AccountCommandResult.Succeeded(id.value))
            val account = storage.load(id)
            val row = storage.code(EnrollmentCodeHash.of(code))!!
            listOf(account.status, account.statusBeforeSuspend, row.usedAt != null, row.revokedAt == null) shouldBe listOf(AccountStatus.SUSPENDED, AccountStatus.ACTIVE, true, true)
            passwordHasher.matches(password.toCharArray(), account.passwordHash) shouldBe true
        }
    }

    // S6(D-64 종료): 이전 행 "RECOVERY codes before S6 — begin에서 NOT_APPLICABLE"은 S6 전 임시 fail-closed였다(§20.2 D-64 "S6에서 추가"). 복구 명령이 생겨
    // RECOVERY 코드 흐름이 열렸으므로, 같은 보호(복구 명령이 만들지 않은 RECOVERY 코드로는 계정을 활성화할 수 없다)를 완료 TX 판정으로 고정한다.
    Given("a RECOVERY code that the recovery command did not issue for this account (S6 — replaces the pre-S6 fail-closed row)") {
        Then("it can be started but never completes: the account's pending reason/basis differs → NOT_APPLICABLE, code unused, account unchanged") {
            val (id, _) = newAccountWithCode()
            val raw = "QRSTUVWXYZ234567ABCDEFGHIJKLMNOP"
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            storage.inTx {
                codes.insert(
                    EnrollmentCode(
                        EnrollmentCodeHash.of(raw), id, EnrollmentPurpose.RECOVERY, "episode:1", 1, setOf(AdminRole.APPROVER), now, now.plusSeconds(600), null, null,
                    ),
                )
            }
            val (draft, secret) = withTotp(withPassword(begin(raw)))
            enrollment.complete(draft, TestTotp.code(secret), context()) shouldBe EnrollmentResult.Failed(EnrollmentFailure.NOT_APPLICABLE)
            storage.code(EnrollmentCodeHash.of(raw))!!.usedAt shouldBe null
            storage.load(id).status shouldBe AccountStatus.PENDING_ENROLLMENT
        }
    }
})
