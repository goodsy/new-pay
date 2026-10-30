package com.paycore.admin.account.adapter.batch

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.adapter.integration.MicrometerAdminAccountMetrics
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.RefreshActiveRoleHoldersUseCase
import com.paycore.admin.support.AdminHttpClient
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.CreateAdminAccount
import com.paycore.admin.account.application.provided.EnrollmentUseCase
import com.paycore.admin.account.application.provided.OneShotCommandUseCase
import com.paycore.admin.account.application.provided.OneShotResult
import com.paycore.admin.account.application.provided.RecoveryCommand
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.RequestApproval
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/**
 * ADMIN-BOOT-002(D-ADMIN1-022): bootstrap은 SECURITY_ADMIN 2명 + APPROVER 1명을 1회성으로 만든다 — bootstrap 직후 역할 부여 교착(409 `CREDENTIAL_ISSUER`)을 푼다.
 * D-ADMIN1-015는 유지: 계정을 만든 SA-A는 그 계정의 `ROLE_GRANT`에서 계속 배제되고, 시스템 근거인 SA-B가 요청하며 APPROVER가 TOTP로 승인한다.
 * 복구 명령(D-ADMIN1-010) 조건은 불변 — SA 2명 중 1명만 남아도 `NOT_NEEDED`. 운영 하한(D-ADMIN1-023)은 활성 역할 수 gauge로 드러낸다.
 * 실제 MySQL(전용 컨텍스트 — 시험마다 표를 비운다), 실제 명령 줄·Use Case 경로, SA-A 요청 거부는 실제 HTTP(2단계 로그인 세션·CSRF)로도 단언한다.
 */
@SpringBootTest(properties = ["paycore.test.context=one-shot-bootstrap"])
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class)
class BootstrapSecondSecurityAdminSpec(
    private val oneShot: OneShotCommandUseCase,
    private val storage: AdminAccountStorage,
    private val enrollment: EnrollmentUseCase,
    private val commands: AdminAccountCommandUseCase,
    private val approvals: ApprovalUseCase,
    private val gauges: RefreshActiveRoleHoldersUseCase,
    private val registry: MeterRegistry,
    private val mockMvc: MockMvc,
    private val container: MySQLContainer,
) : BehaviorSpec({

    beforeEach { OneShotSupport.wipe(container) }
    fun count(table: String): Int = AdminDbAccounts.root(container).use { (it.scalar("SELECT COUNT(*) FROM $table") as Number).toInt() }
    fun audits(): List<String> = AdminDbAccounts.root(container).use {
        it.column("SELECT CONCAT(actor_kind, ':', action, ':', result_code, ':', COALESCE(detail, '-')) FROM admin_audit_event ORDER BY event_id")
    }
    fun idOf(login: String): AdminActorId =
        AdminActorId(AdminDbAccounts.root(container).use { it.scalar("SELECT actor_id FROM admin_account WHERE login_name = ?", login) as String })
    fun options(output: String) = mapOf(
        "output" to output,
        "security-admin-login" to "sa-alpha", "security-admin-name" to "보안 관리자 A",
        "second-security-admin-login" to "sa-bravo", "second-security-admin-name" to "보안 관리자 B",
        "approver-login" to "apr-one", "approver-name" to "승인자",
    )
    fun cli() = OneShotCommandLine(oneShot, OneShotSupport.FakeConsole(false))
    fun bootstrap(): Map<String, String> {
        val file = OneShotSupport.outputFile()
        cli().run("bootstrap", options("file:$file")) shouldBe OneShotExit.SUCCESS.code
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
        // 리뷰 Minor-2: 비주석 줄은 정확히 3개, 순서 SA-A → SA-B → APPROVER(목적 ENROLL).
        Files.readString(file).lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t').take(2) } shouldBe
            listOf(listOf("sa-alpha", "ENROLL"), listOf("sa-bravo", "ENROLL"), listOf("apr-one", "ENROLL"))
        return OneShotSupport.codesIn(Files.readString(file))
    }
    fun gauge(role: AdminRole): Double? = registry.find(MicrometerAdminAccountMetrics.ACTIVE_ROLE_HOLDERS).tag("role", role.name).gauge()?.value()
    fun httpLogin(login: String, secret: ByteArray): AdminHttpClient =
        AdminHttpClient(mockMvc).also { it.login(login, "one-shot-password-1", secret, Instant.now().plusSeconds(30)).status shouldBe 200 }
    fun httpGrant(client: AdminHttpClient, target: AdminActorId, role: AdminRole) = client.perform(
        post("/api/approvals").contentType(MediaType.APPLICATION_JSON)
            .content(AdminHttpClient.json("kind" to "ROLE_GRANT", "targetActorId" to target.value, "grantedRole" to role.name)),
    )
    fun gen(id: AdminActorId) = storage.load(id).sessionGeneration
    fun grantRequest(requester: AdminActorId, target: AdminActorId, role: AdminRole): ApprovalOutcome =
        approvals.request(RequestApproval(requester.value, gen(requester), "ROLE_GRANT", target.value, role.name, null, OneShotSupport.context()))

    Given("an empty ADMIN DB and the bootstrap command with two SECURITY_ADMINs and one APPROVER") {
        Then("one run creates SA-A, SA-B (both system basis — no issuer) and the APPROVER, one fixed row, three codes only in the 0600 file, audited without code text") {
            val codes = bootstrap()
            codes.keys shouldBe setOf("sa-alpha", "sa-bravo", "apr-one")
            listOf(count("admin_account"), count("admin_bootstrap"), count("admin_enrollment_code")) shouldBe listOf(3, 1, 3)
            val (a, b, apr) = listOf("sa-alpha", "sa-bravo", "apr-one").map { storage.load(idOf(it)) }
            listOf(a.activeRoles, b.activeRoles, apr.activeRoles) shouldBe listOf(setOf(AdminRole.SECURITY_ADMIN), setOf(AdminRole.SECURITY_ADMIN), setOf(AdminRole.APPROVER))
            listOf(a, b, apr).map { it.status to it.credentialBasis.issuers } shouldBe List(3) { AccountStatus.PENDING_ENROLLMENT to emptySet<AdminActorId>() }
            // 고정 행(I-4): 열은 SA-A·APPROVER, SA-B는 감사·역할 이벤트로 추적.
            AdminDbAccounts.root(container).use { it.column("SELECT CONCAT(security_admin_actor_id, '/', approver_actor_id) FROM admin_bootstrap") } shouldBe
                listOf("${a.id.value}/${apr.id.value}")
            val rows = audits()
            rows.size shouldBe 7
            rows.all { it.startsWith("SYSTEM_BOOTSTRAP:") } shouldBe true
            rows.count { it == "SYSTEM_BOOTSTRAP:ACCOUNT_CREATED:SUCCESS:role=SECURITY_ADMIN" } shouldBe 2
            rows.count { it == "SYSTEM_BOOTSTRAP:BOOTSTRAP:SUCCESS:accounts=3" } shouldBe 1
            rows.none { row -> codes.values.any { row.contains(it) } } shouldBe true
            AdminDbAccounts.root(container).use { (it.scalar("SELECT COUNT(*) FROM admin_role_event WHERE actor_kind = 'SYSTEM_BOOTSTRAP' AND role = 'SECURITY_ADMIN'") as Number).toInt() } shouldBe 2
        }

        Then("D-ADMIN1-015 kept: SA-A cannot request ROLE_GRANT on the account it created (CREDENTIAL_ISSUER); SA-B requests, the APPROVER approves with TOTP → OPERATOR granted") {
            val codes = bootstrap()
            val (a, b, apr) = listOf("sa-alpha", "sa-bravo", "apr-one").map { idOf(it) }
            val secretA = OneShotSupport.enroll(enrollment, a.value, codes.getValue("sa-alpha"))
            val secretB = OneShotSupport.enroll(enrollment, b.value, codes.getValue("sa-bravo"))
            val approverSecret = OneShotSupport.enroll(enrollment, apr.value, codes.getValue("apr-one"))
            val created = commands.createAccount(CreateAdminAccount(a.value, "ops-demo", "운영자", OneShotSupport.context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            val target = AdminActorId(created.targetActorId)
            storage.load(target).credentialBasis.issuers shouldBe setOf(a)
            OneShotSupport.enroll(enrollment, target.value, requireNotNull(created.enrollmentCode))

            // 리뷰 Minor-1: Demo에서 본 그대로 실제 HTTP 409 CREDENTIAL_ISSUER(로그인 TOTP는 등록이 소비한 단계 다음 단계).
            val saA = httpLogin("sa-alpha", secretA)
            httpGrant(saA, target, AdminRole.OPERATOR).let { it.status to it.contentAsString } shouldBe (409 to """{"error":"CREDENTIAL_ISSUER"}""")
            grantRequest(a, target, AdminRole.OPERATOR) shouldBe ApprovalOutcome.Rejected("CREDENTIAL_ISSUER")
            val created201 = httpGrant(httpLogin("sa-bravo", secretB), target, AdminRole.OPERATOR)
            created201.status shouldBe 201
            val approvalId = Regex("\"approvalId\":\"(apr_[0-9A-Z]+)\"").find(created201.contentAsString)!!.groupValues[1]
            // 등록 완료가 현재 TOTP 단계를 소비했으므로 다음 단계 코드(창 ±1)로 승인한다.
            approvals.approve(
                DecideApproval(approvalId, apr.value, gen(apr), TestTotp.code(approverSecret, Instant.now().plusSeconds(30)), OneShotSupport.context()),
            ).shouldBeInstanceOf<ApprovalOutcome.Executed>()
            storage.load(target).activeRoles shouldBe setOf(AdminRole.OPERATOR)
            // 같은 계정에 대한 SA-A의 권한 상승 배제는 부여 뒤에도 유지된다.
            httpGrant(saA, target, AdminRole.VIEWER).let { it.status to it.contentAsString } shouldBe (409 to """{"error":"CREDENTIAL_ISSUER"}""")
        }

        Then("a second bootstrap after success is refused (ACCOUNTS_EXIST) — nothing changes, the refusal is audited") {
            bootstrap()
            val before = audits().size
            cli().run(
                "bootstrap",
                options("file:${OneShotSupport.outputFile()}") + mapOf("security-admin-login" to "sa-other", "second-security-admin-login" to "sa-other2", "approver-login" to "apr-other"),
            ) shouldBe OneShotExit.CONDITION_REFUSED.code
            listOf(count("admin_account"), count("admin_bootstrap"), count("admin_enrollment_code")) shouldBe listOf(3, 1, 3)
            audits().drop(before) shouldBe listOf("SYSTEM_BOOTSTRAP:BOOTSTRAP:REFUSED:reason=ACCOUNTS_EXIST")
        }

        Then("a missing second SA (the old four-argument form) or a duplicate login/display name among the three is a usage error before any output file or DB change") {
            val base = options("file:placeholder")
            val cases = listOf(
                base - "second-security-admin-login" - "second-security-admin-name",
                base - "second-security-admin-name",
                base + ("second-security-admin-login" to "sa-alpha"),
                base + ("second-security-admin-login" to "apr-one"),
                base + ("second-security-admin-name" to "보안 관리자 A"),
                base + ("approver-name" to "보안 관리자 B"),
            )
            cases.forEach { case ->
                val file = OneShotSupport.outputFile()
                cli().run("bootstrap", case + ("output" to "file:$file")) shouldBe OneShotExit.USAGE.code
                Files.exists(file) shouldBe false
            }
            listOf(count("admin_account"), count("admin_bootstrap"), count("admin_enrollment_code"), count("admin_audit_event")) shouldBe listOf(0, 0, 0, 0)
        }
    }

    Given("two SECURITY_ADMINs after bootstrap, one of them suspended (D-ADMIN1-010 unchanged)") {
        Then("recovery is refused NOT_NEEDED while one active SECURITY_ADMIN and one active APPROVER remain") {
            val codes = bootstrap()
            val (a, b, apr) = listOf("sa-alpha", "sa-bravo", "apr-one").map { idOf(it) }
            OneShotSupport.enroll(enrollment, a.value, codes.getValue("sa-alpha"))
            OneShotSupport.enroll(enrollment, b.value, codes.getValue("sa-bravo"))
            OneShotSupport.enroll(enrollment, apr.value, codes.getValue("apr-one"))
            commands.suspend(TargetedAccountCommand(b.value, a.value, OneShotSupport.context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            oneShot.recover(RecoveryCommand(listOf("sa-alpha"), null, OneShotSupport.context().correlationId)) shouldBe OneShotResult.Refused("NOT_NEEDED")
            count("admin_recovery_episode") shouldBe 0
        }
    }

    Given("active role holder gauges (D-ADMIN1-023 — LIVE floor: active SECURITY_ADMIN ≥ 2, active APPROVER ≥ 2; below it is an alert)") {
        Then("the gauge counts ACTIVE accounts holding the role: 0 before enrollment, SA 2 · APPROVER 1 after, SA 1 while SA-A is suspended, SA 2 again after ACCOUNT_REACTIVATE") {
            val codes = bootstrap()
            val (a, b, apr) = listOf("sa-alpha", "sa-bravo", "apr-one").map { idOf(it) }
            val roles = listOf(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)
            fun observed() = roles.map { gauge(it) }

            gauges.refresh() shouldBe mapOf("SECURITY_ADMIN" to 0, "APPROVER" to 0) // 등록 전 PENDING_ENROLLMENT은 활성 아님
            observed() shouldBe listOf(0.0, 0.0)

            OneShotSupport.enroll(enrollment, a.value, codes.getValue("sa-alpha"))
            OneShotSupport.enroll(enrollment, b.value, codes.getValue("sa-bravo"))
            val approverSecret = OneShotSupport.enroll(enrollment, apr.value, codes.getValue("apr-one"))
            gauges.refresh() shouldBe mapOf("SECURITY_ADMIN" to 2, "APPROVER" to 1) // bootstrap 직후 APPROVER는 하한(2) 미만 — 경보 대상
            observed() shouldBe listOf(2.0, 1.0)

            commands.suspend(TargetedAccountCommand(b.value, a.value, OneShotSupport.context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            observed() shouldBe listOf(2.0, 1.0) // 갱신 전에는 직전 값(주기 갱신 — 지연은 갱신 주기)
            gauges.refresh() shouldBe mapOf("SECURITY_ADMIN" to 1, "APPROVER" to 1)
            observed() shouldBe listOf(1.0, 1.0)

            val reactivation = approvals.request(RequestApproval(b.value, gen(b), "ACCOUNT_REACTIVATE", a.value, null, null, OneShotSupport.context()))
                .shouldBeInstanceOf<ApprovalOutcome.Created>().approvalId
            approvals.approve(
                DecideApproval(reactivation, apr.value, gen(apr), TestTotp.code(approverSecret, Instant.now().plusSeconds(30)), OneShotSupport.context()),
            ).shouldBeInstanceOf<ApprovalOutcome.Executed>()
            gauges.refresh() shouldBe mapOf("SECURITY_ADMIN" to 2, "APPROVER" to 1)
            observed() shouldBe listOf(2.0, 1.0)
        }
    }
})
