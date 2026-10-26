package com.paycore.admin.account.adapter.batch

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.BootstrapCommand
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import com.paycore.admin.account.application.provided.EnrollmentUseCase
import com.paycore.admin.account.application.provided.NewAdminAccount
import com.paycore.admin.account.application.provided.OneShotCommandUseCase
import com.paycore.admin.account.application.provided.OneShotResult
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.RowLockHolder
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 부트스트랩(§4.9.1 — `BootstrapSpec`·`BootstrapConcurrencySpec`) + 1회성 명령 출력(§4.9 — TTY 또는 `CREATE_NEW` 0600 파일, logger 없음). 전용 컨텍스트(빈 표 조건) —
 * 시험마다 표를 비운다. 명령 줄 해석·출력은 adapter `OneShotCommandLine`(시험 대역 콘솔), DB 강제는 Use Case.
 */
@SpringBootTest(properties = ["paycore.test.context=one-shot-bootstrap"])
@AdminDbTest
@Import(AdminAccountStorage::class)
class BootstrapCommandSpec(
    private val oneShot: OneShotCommandUseCase,
    private val storage: AdminAccountStorage,
    private val enrollment: EnrollmentUseCase,
    private val commands: AdminAccountCommandUseCase,
    private val container: MySQLContainer,
) : BehaviorSpec({

    beforeEach { OneShotSupport.wipe(container) }
    fun count(table: String): Int = AdminDbAccounts.root(container).use { (it.scalar("SELECT COUNT(*) FROM $table") as Number).toInt() }
    fun audits(): List<String> = AdminDbAccounts.root(container).use {
        it.column("SELECT CONCAT(actor_kind, ':', action, ':', result_code, ':', COALESCE(detail, '-')) FROM admin_audit_event ORDER BY event_id")
    }
    fun options(output: String) = mapOf(
        "output" to output, "security-admin-login" to "boot-sa", "security-admin-name" to "보안 관리자", "approver-login" to "boot-apr", "approver-name" to "승인자",
    )
    fun line(terminal: Boolean = false) = OneShotSupport.FakeConsole(terminal).let { it to OneShotCommandLine(oneShot, it) }

    Given("an empty ADMIN DB") {
        Then("bootstrap creates exactly one SECURITY_ADMIN and one APPROVER (system basis, PENDING_ENROLLMENT), the fixed row, two ENROLL codes written only to a new 0600 file") {
            val file = OneShotSupport.outputFile()
            val (_, cli) = line()
            cli.run("bootstrap", options("file:$file")) shouldBe OneShotExit.SUCCESS.code
            PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
            val codes = OneShotSupport.codesIn(Files.readString(file))
            codes.keys shouldBe setOf("boot-sa", "boot-apr")
            listOf(count("admin_account"), count("admin_bootstrap"), count("admin_enrollment_code")) shouldBe listOf(2, 1, 2)
            val ids = AdminDbAccounts.root(container).use { it.column("SELECT actor_id FROM admin_account ORDER BY login_name") }
            val (approver, admin) = ids.map { storage.load(AdminActorId(it)) }
            listOf(admin.activeRoles, approver.activeRoles, admin.status, admin.credentialBasis.issuers) shouldBe
                listOf(setOf(AdminRole.SECURITY_ADMIN), setOf(AdminRole.APPROVER), AccountStatus.PENDING_ENROLLMENT, emptySet<AdminActorId>())
            // S8 리뷰 nit-6: 빈 목록의 all{}은 참이므로 행 수를 먼저 고정한다(계정 생성 2·코드 발급 2·부트스트랩 1).
            audits().size shouldBe 5
            audits().all { it.startsWith("SYSTEM_BOOTSTRAP:") } shouldBe true
            audits().none { row -> codes.values.any { row.contains(it) } } shouldBe true
            OneShotSupport.enroll(enrollment, admin.id.value, codes.getValue("boot-sa"))
            storage.load(admin.id).let { it.status to it.activeRoles } shouldBe (AccountStatus.ACTIVE to setOf(AdminRole.SECURITY_ADMIN))
            // EnrollReissueRoleBearingForbidden(rev 0.4 H-1): 역할 보유 대기 계정(부트스트랩 APPROVER)의 코드는 1인 재발급 불가 — 2인 재설정 또는 복구로만.
            commands.reissueEnrollCode(TargetedAccountCommand(admin.id.value, approver.id.value, OneShotSupport.context())) shouldBe
                AccountCommandResult.Rejected("ROLE_BEARING_ACCOUNT")
        }
        Then("TTY output without a terminal, an existing output file, or a missing/duplicate argument is refused BEFORE any DB change (distinct exit codes)") {
            val existing = OneShotSupport.outputFile().also { Files.writeString(it, "keep") }
            listOf(
                line(terminal = false).second.run("bootstrap", options("tty")),
                line().second.run("bootstrap", options("file:$existing")),
                line().second.run("bootstrap", options("file:${OneShotSupport.outputFile()}") - "approver-name"),
                line().second.run("bootstrap", options("file:${OneShotSupport.outputFile()}") + ("approver-login" to "boot-sa")),
                line().second.run("unknown", options("tty")),
            ) shouldBe listOf(OneShotExit.OUTPUT_REFUSED.code, OneShotExit.OUTPUT_REFUSED.code, OneShotExit.USAGE.code, OneShotExit.USAGE.code, OneShotExit.USAGE.code)
            Files.readString(existing) shouldBe "keep"
            listOf(count("admin_account"), count("admin_bootstrap")) shouldBe listOf(0, 0)
            audits().count { it.startsWith("SYSTEM_BOOTSTRAP:BOOTSTRAP:REFUSED:reason=OUTPUT") } shouldBe 2
        }
        Then("S6 review M-1: the prepared file swapped for a symlink before commit → nothing is written through the link, exit 7, the link target unchanged") {
            val file = OneShotSupport.outputFile()
            val target = file.resolveSibling("victim.txt").also { Files.writeString(it, "victim") }
            // Use Case 직전(출력 준비 뒤·커밋 전)에 준비된 파일을 지우고 같은 이름의 symlink로 바꾸는 대역.
            val swapping = object : OneShotCommandUseCase by oneShot {
                override fun bootstrap(command: BootstrapCommand): OneShotResult {
                    Files.delete(file)
                    Files.createSymbolicLink(file, target)
                    return oneShot.bootstrap(command)
                }
            }
            OneShotCommandLine(swapping, OneShotSupport.FakeConsole(false)).run("bootstrap", options("file:$file")) shouldBe OneShotExit.OUTPUT_FAILED.code
            Files.readString(target) shouldBe "victim"
            Files.isSymbolicLink(file) shouldBe true
            count("admin_account") shouldBe 2 // 코드는 커밋됐다 — 재발급 경로(복구 명령)로 간다.
        }
        Then("S6 review M-1: an output directory writable by group/others is refused before any DB change") {
            val shared = Files.createTempDirectory("paycore-shared-").also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxrwxrwx")) }
            line().second.run("bootstrap", options("file:${shared.resolve("codes.txt")}")) shouldBe OneShotExit.OUTPUT_REFUSED.code
            Files.exists(shared.resolve("codes.txt")) shouldBe false
            count("admin_account") shouldBe 0
            audits() shouldBe listOf("SYSTEM_BOOTSTRAP:BOOTSTRAP:REFUSED:reason=OUTPUT_DIRECTORY_SHARED")
        }
        Then("a TTY (terminal present) receives the codes") {
            val (console, cli) = line(terminal = true)
            cli.run("bootstrap", options("tty")) shouldBe OneShotExit.SUCCESS.code
            OneShotSupport.codesIn(console.written.toString()).keys shouldBe setOf("boot-sa", "boot-apr")
        }
    }

    Given("accounts already exist") {
        Then("bootstrap is refused (condition), nothing changes, the refusal is audited in a separate TX") {
            storage.securityAdmin()
            line().second.run("bootstrap", options("file:${OneShotSupport.outputFile()}")) shouldBe OneShotExit.CONDITION_REFUSED.code
            listOf(count("admin_account"), count("admin_bootstrap")) shouldBe listOf(1, 0)
            audits() shouldBe listOf("SYSTEM_BOOTSTRAP:BOOTSTRAP:REFUSED:reason=ACCOUNTS_EXIST")
        }
    }

    Given("two bootstraps at once (BootstrapConcurrency — both proven to wait on the fixed row)") {
        Then("exactly one pair of accounts, one fixed row, the loser gets LOST with its own audit row") {
            val pool = Executors.newFixedThreadPool(2)
            val command = { suffix: String ->
                BootstrapCommand(NewAdminAccount("sa-$suffix", "보안 $suffix"), NewAdminAccount("apr-$suffix", "승인 $suffix"), "corr-boot-$suffix")
            }
            val results = RowLockHolder.open(container).use { holder ->
                // 세 번째 연결이 첫 부트스트랩의 SECURITY_ADMIN login_name으로 계정 행을 미커밋 INSERT → 첫 TX는 고정 행을 쥔 채 그 UNIQUE에서 멈춘다.
                // 그동안 둘째 TX가 고정 행 PK에서 첫 TX를 기다리는 것을 확인한 뒤 놓는다(둘 다 같은 PK를 기다리게 하면 S→X 승격 교착이 난다 — 실측).
                holder.update(
                    "INSERT INTO admin_account (actor_id, login_name, display_name, status, credential_basis, active_roles, suspended_roles, failed_attempts, " +
                        "session_generation, security_version, version, created_at, updated_at) " +
                        "VALUES ('adm_HOLDER', 'sa-a', 'Holder', 'PENDING_ENROLLMENT', 'x', '', '', 0, 0, 0, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                )
                holder.lock("admin_account", "actor_id", "adm_HOLDER")
                val first = pool.submit(Callable { oneShot.bootstrap(command("a")) })
                holder.awaitWaiters("admin_account", 1)
                val second = pool.submit(Callable { oneShot.bootstrap(command("b")) })
                holder.awaitWaiters("admin_bootstrap", 1, blockedByHolder = false)
                holder.release()
                listOf(first, second).map { it.get(60, TimeUnit.SECONDS) }
            }
            pool.shutdown()
            results.count { it is OneShotResult.Done } shouldBe 1
            results.count { it == OneShotResult.Lost } shouldBe 1
            listOf(count("admin_account"), count("admin_bootstrap"), count("admin_enrollment_code")) shouldBe listOf(2, 1, 2)
            audits().count { it == "SYSTEM_BOOTSTRAP:BOOTSTRAP:LOST:reason=ALREADY_BOOTSTRAPPED" } shouldBe 1
            results.first { it is OneShotResult.Done }.shouldBeInstanceOf<OneShotResult.Done>().codes.size shouldBe 2
        }
    }
})
