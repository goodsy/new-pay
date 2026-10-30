package com.paycore.admin.account.adapter.batch

import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.mysql.MySQLContainer

/** 1회성 명령 모드 가드(§4.9 — `OneShotModeGuardSpec`): 비웹이라도 스케줄러(만료 정리 job)가 켜져 있으면 기동 거부. 실제 `ApplicationRunner` 경로(시험 `args`). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["paycore.admin.approval.expiry-job.enabled=true"], 
    args = ["--paycore.admin.command=bootstrap", "--output=tty", "--security-admin-login=g-sa", "--security-admin-name=SA", "--second-security-admin-login=g-sa2", "--second-security-admin-name=SA2", "--approver-login=g-apr", "--approver-name=APR"],
)
@AdminDbTest
class OneShotModeSchedulerSpec(private val runner: OneShotCommandRunner, private val container: MySQLContainer) : FunSpec({
    test("a non-web context with the scheduler enabled refuses the command") {
        runner.exitCode shouldBe OneShotExit.MODE_REFUSED.code
        AdminDbAccounts.root(container).use { (it.scalar("SELECT COUNT(*) FROM admin_account") as Number).toInt() } shouldBe 0
    }
})
