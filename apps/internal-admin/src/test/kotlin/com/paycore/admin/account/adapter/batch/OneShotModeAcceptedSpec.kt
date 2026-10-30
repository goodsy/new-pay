package com.paycore.admin.account.adapter.batch

import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.testcontainers.mysql.MySQLContainer

/** 1회성 명령 모드 가드(§4.9 — `OneShotModeGuardSpec`): 비웹 + 스케줄러 꺼짐이면 모드 가드를 통과한다 — 시험 JVM에는 터미널이 없어 TTY 출력은 DB 변경 전 거부(실제 콘솔 접근 경로). 실제 `ApplicationRunner` 경로(시험 `args`). */
// ADMIN-BOOT-002(D-ADMIN1-023): 활성 역할 gauge job을 켜 둔 채로도 명령 모드에서는 그 job이 등록되지 않는다(명령 모드는 스케줄러 없이).
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["paycore.admin.accounts.active-role-gauge.enabled=true"],
    args = ["--paycore.admin.command=bootstrap", "--output=tty", "--security-admin-login=g-sa", "--security-admin-name=SA", "--second-security-admin-login=g-sa2", "--second-security-admin-name=SA2", "--approver-login=g-apr", "--approver-name=APR"],
)
@AdminDbTest
class OneShotModeAcceptedSpec(private val runner: OneShotCommandRunner, private val context: ApplicationContext, private val container: MySQLContainer) : FunSpec({
    test("a non-web context with the scheduler off passes the guard; TTY output without a terminal is refused before any DB change") {
        runner.exitCode shouldBe OneShotExit.OUTPUT_REFUSED.code
        AdminDbAccounts.root(container).use { (it.scalar("SELECT COUNT(*) FROM admin_account") as Number).toInt() } shouldBe 0
    }
    test("command mode never registers the active role gauge job even when it is enabled") {
        context.getBeanProvider(ActiveRoleHolderGaugeScheduler::class.java).ifAvailable shouldBe null
    }
})
