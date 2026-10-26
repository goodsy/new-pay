package com.paycore.admin.security

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.adapter.integration.MicrometerAdminAccountMetrics
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AuthenticationFailureRecorder
import com.paycore.admin.account.application.provided.AuthenticationStep
import com.paycore.admin.account.application.required.PersistenceFailure
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.approval.adapter.integration.MicrometerApprovalMetrics
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.RowLockHolder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID

/**
 * 관측 지표(S8 — §6, label은 고정 분류값만): 로그인 단계 결과·잠금, IP 거부(경로 분류), 등록 코드 무효(D-67 "지표만"), 승인 결과, 재인증 실패 계수 누락(D-ADMIN1-016 —
 * 경보 대상). 지표는 커밋된 결과만 센다. 컨텍스트는 `ApprovalLockOrderSpec`과 같은 설정(같은 캐시 컨텍스트 — `innodb_lock_wait_timeout = 3`).
 */
@SpringBootTest(properties = ["spring.datasource.hikari.data-source-properties.sessionVariables=innodb_lock_wait_timeout=3"])
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class AdminObservabilitySpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val path: ApprovalPath,
    private val failures: AuthenticationFailureRecorder,
    private val registry: MeterRegistry,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun count(name: String, vararg tags: String): Double = registry.find(name).tags(*tags).counter()?.count() ?: 0.0
    fun login(step: String, result: String) = count(MicrometerAdminAccountMetrics.LOGIN, "step", step, "result", result)
    fun browser(ip: String = "127.0.0.1") = AdminHttpClient(mockMvc, remoteAddr = ip).also { it.csrf() }

    Given("the two login steps (paycore.admin.login{step,result}, paycore.admin.lockout{step})") {
        Then("a wrong password, an unknown account and a full login each count once under their committed result") {
            val account = ready.withoutRoles()
            val (failed, unknown, passed, totp) = listOf(login("PASSWORD", "FAILED"), login("PASSWORD", "UNKNOWN_ACCOUNT"), login("PASSWORD", "SUCCESS"), login("TOTP", "SUCCESS"))
            browser().password(account.loginName, "wrong-password-0000")
            browser().password("nobody-" + UUID.randomUUID().toString().take(8), "wrong-password-0000")
            AdminHttpClient(mockMvc).login(account.loginName, account.password, account.secret).status shouldBe 200
            listOf(login("PASSWORD", "FAILED"), login("PASSWORD", "UNKNOWN_ACCOUNT"), login("PASSWORD", "SUCCESS"), login("TOTP", "SUCCESS")) shouldBe
                listOf(failed + 1, unknown + 1, passed + 1, totp + 1)
        }
        Then("the failure that reaches the limit counts as LOCKED and one lockout; the next attempt is NOT_COUNTED") {
            val account = ready.withoutRoles()
            val (locked, lockouts, notCounted) = listOf(login("PASSWORD", "LOCKED"), count(MicrometerAdminAccountMetrics.LOCKOUT, "step", "PASSWORD"), login("PASSWORD", "NOT_COUNTED"))
            // 한도 = paycore.admin.security.login-max-failures(Demo 5 — application.yml).
            repeat(LOGIN_MAX_FAILURES) { browser().password(account.loginName, "wrong-password-0000") }
            browser().password(account.loginName, "wrong-password-0000")
            listOf(login("PASSWORD", "LOCKED"), count(MicrometerAdminAccountMetrics.LOCKOUT, "step", "PASSWORD"), login("PASSWORD", "NOT_COUNTED")) shouldBe
                listOf(locked + 1, lockouts + 1, notCounted + 1)
        }
    }

    Given("IP denial (paycore.admin.ip_denied{area} — every denial, audited or not)") {
        Then("an authentication path counts as AUTH, an enrollment path as ENROLL and any other path as OTHER") {
            val before = listOf("AUTH", "ENROLL", "OTHER").map { count("paycore.admin.ip_denied", "area", it) }
            val outside = AdminHttpClient(mockMvc, remoteAddr = "192.0.2.10")
            outside.perform(get("/api/auth/csrf")).status shouldBe 403
            outside.perform(post("/api/enroll/start").contentType(MediaType.APPLICATION_JSON).content("{}")).status shouldBe 403
            outside.perform(get("/api/audit")).status shouldBe 403
            listOf("AUTH", "ENROLL", "OTHER").map { count("paycore.admin.ip_denied", "area", it) } shouldBe before.map { it + 1 }
        }
    }

    Given("an invalid enrollment code (D-67: an anonymous attempt is a metric, not an audit row)") {
        Then("paycore.admin.enrollment{operation=BEGIN,result=CODE_INVALID} counts it") {
            val before = count(MicrometerAdminAccountMetrics.ENROLLMENT, "operation", "BEGIN", "result", "CODE_INVALID")
            val c = browser()
            c.perform(post("/api/enroll/start").contentType(MediaType.APPLICATION_JSON).content(AdminHttpClient.json("code" to "A".repeat(32)))).status shouldBe 400
            count(MicrometerAdminAccountMetrics.ENROLLMENT, "operation", "BEGIN", "result", "CODE_INVALID") shouldBe before + 1
        }
    }

    Given("2-person approval decisions (paycore.admin.approval{operation,kind,result})") {
        Then("request CREATED, approve EXECUTED and a second approve ALREADY_DECIDED each count once with the kind label") {
            fun approval(operation: String, result: String) = count(MicrometerApprovalMetrics.DECISION, "operation", operation, "kind", "ROLE_GRANT", "result", result)
            val before = listOf(approval("REQUEST", "CREATED"), approval("APPROVE", "EXECUTED"), approval("APPROVE", "ALREADY_DECIDED"))
            val id = path.request("ROLE_GRANT", storage.save(AdminAccountFixture.enrolledActive()).id, "VIEWER")
            path.approve(id).shouldBeInstanceOf<ApprovalOutcome.Executed>()
            path.approve(id) shouldBe ApprovalOutcome.AlreadyDecided
            listOf(approval("REQUEST", "CREATED"), approval("APPROVE", "EXECUTED"), approval("APPROVE", "ALREADY_DECIDED")) shouldBe before.map { it + 1 }
        }
    }

    Given("D-ADMIN1-016: the step-up failure counting TX still fails after its 3 attempts") {
        Then("that one failure is not counted (current behaviour kept) and paycore.admin.authentication_failure.not_recorded{step,failure} makes it visible") {
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val metric = { count(MicrometerAdminAccountMetrics.AUTHENTICATION_FAILURE_NOT_RECORDED, "step", "STEP_UP_TOTP", "failure", "LOCK_TIMEOUT") }
            val before = metric()
            RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", approver.account.id.value)
                shouldThrow<PersistenceFailure.LockTimeout> {
                    failures.recordFailure(approver.account.id.value, AuthenticationStep.STEP_UP_TOTP, AdminRequestContext("corr-obs", "10.0.0.9"))
                }
                holder.release()
            }
            metric() shouldBe before + 1
            storage.load(approver.account.id).failedAttempts shouldBe 0
        }
    }
}) {
    private companion object {
        const val LOGIN_MAX_FAILURES = 5
    }
}
