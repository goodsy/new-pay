package com.paycore.admin.audit

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AuthenticationFailureRecorder
import com.paycore.admin.account.application.provided.AuthenticationStep
import com.paycore.admin.account.application.provided.CreateAdminAccount
import com.paycore.admin.account.application.provided.EnrollmentResult
import com.paycore.admin.account.application.provided.EnrollmentUseCase
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.EnrollmentCodeHash
import com.paycore.admin.account.domain.LoginName
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.InjectedAuditFailure
import com.paycore.admin.support.SwitchableAuditStore
import com.paycore.admin.support.SwitchableAuditStoreConfig
import com.paycore.admin.support.TestTotp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID

/**
 * 감사 저장 실패 = 행위 실패(§4.6 `AuditFailureClosesActionSpec`). 감사 append가 실패하면 같은 로컬 TX의 상태 변경(계정·코드·역할 이벤트)이 모두 rollback되고
 * 성공 결과를 돌려주지 않는다(예외 전파 → 웹 5xx). 실패 주입 위치는 실제 저장 Port 호출 지점([SwitchableAuditStore]).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, SwitchableAuditStoreConfig::class)
class AuditFailureClosesActionSpec(
    private val commands: AdminAccountCommandUseCase,
    private val enrollment: EnrollmentUseCase,
    private val failures: AuthenticationFailureRecorder,
    private val accounts: AdminAccountRepository,
    private val auditStore: SwitchableAuditStore,
    private val storage: AdminAccountStorage,
) : BehaviorSpec({

    fun context() = AdminRequestContext("corr-" + UUID.randomUUID().toString().take(8), "10.0.0.4")
    afterEach { auditStore.failOn(null) }

    Given("an audit store that fails for the action under test") {
        Then("account creation: no account and no code remain") {
            val admin = storage.securityAdmin()
            val login = "af-" + UUID.randomUUID().toString().take(8)
            auditStore.failOn { it.action == "ENROLLMENT_CODE_ISSUED" }
            shouldThrow<InjectedAuditFailure> { commands.createAccount(CreateAdminAccount(admin.id.value, login, "감사실패", context())) }
            storage.inTx { accounts.findByLoginName(LoginName(login)) } shouldBe null
        }
        Then("suspension: the target stays as it was and its enrollment code stays usable") {
            val admin = storage.securityAdmin()
            val created = commands.createAccount(CreateAdminAccount(admin.id.value, "af-" + UUID.randomUUID().toString().take(8), "대상", context()))
                as AccountCommandResult.Succeeded
            auditStore.failOn { it.action == "ACCOUNT_SUSPENDED" }
            shouldThrow<InjectedAuditFailure> { commands.suspend(TargetedAccountCommand(admin.id.value, created.targetActorId, context())) }
            val target = storage.load(com.paycore.admin.account.domain.AdminActorId(created.targetActorId))
            target.status shouldBe AccountStatus.PENDING_ENROLLMENT
            storage.code(EnrollmentCodeHash.of(created.enrollmentCode!!))!!.revokedAt shouldBe null
        }
        Then("a denial is not reported when its audit row cannot be written") {
            val roleless = storage.save(AdminAccountFixture.enrolledActive())
            auditStore.failOn { it.resultCode == "DENIED" }
            shouldThrow<InjectedAuditFailure> { commands.createAccount(CreateAdminAccount(roleless.id.value, "af-denied", "거부", context())) }
        }
        Then("enrollment completion: the code is not consumed and the account is not activated") {
            val admin = storage.securityAdmin()
            val created = commands.createAccount(CreateAdminAccount(admin.id.value, "af-" + UUID.randomUUID().toString().take(8), "등록", context()))
                as AccountCommandResult.Succeeded
            val draft = (enrollment.begin(created.enrollmentCode!!) as EnrollmentResult.InProgress).draft
            val withPassword = (enrollment.providePassword(draft, "correct-horse-battery".toCharArray()) as EnrollmentResult.InProgress).draft
            val provisioning = enrollment.startTotp(withPassword) as EnrollmentResult.TotpProvisioning
            auditStore.failOn { it.action == "ENROLLMENT_COMPLETED" }
            shouldThrow<InjectedAuditFailure> {
                enrollment.complete(provisioning.draft, TestTotp.code(TestTotp.secretOf(provisioning.otpauthUri)), context())
            }
            storage.load(com.paycore.admin.account.domain.AdminActorId(created.targetActorId)).status shouldBe AccountStatus.PENDING_ENROLLMENT
            storage.code(EnrollmentCodeHash.of(created.enrollmentCode))!!.usedAt shouldBe null
        }
        Then("an authentication failure: the counter does not move") {
            val account = storage.save(AdminAccountFixture.enrolledActive())
            auditStore.failOn { it.action == "AUTHENTICATION_FAILED" }
            shouldThrow<InjectedAuditFailure> { failures.recordFailure(account.id.value, AuthenticationStep.PASSWORD, context()) }
            storage.load(account.id).failedAttempts shouldBe 0
        }
    }
})
