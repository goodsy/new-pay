package com.paycore.admin.account.adapter.batch

import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.mysql.MySQLContainer

/** 1회성 명령 모드 가드(§4.9 — `OneShotModeGuardSpec`): 웹 컨텍스트(서블릿 — MOCK)에 명령 인자가 오면 기동 거부(웹 프로필 프로세스는 명령 모드 인자를 거부). 실제 `ApplicationRunner` 경로(시험 `args`). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK, 
    args = ["--paycore.admin.command=bootstrap", "--output=tty", "--security-admin-login=g-sa", "--security-admin-name=SA", "--approver-login=g-apr", "--approver-name=APR"],
)
@AdminDbTest
class OneShotModeWebSpec(private val runner: OneShotCommandRunner, private val container: MySQLContainer) : FunSpec({
    test("a web application context with the command argument refuses the command before any DB access") {
        runner.exitCode shouldBe OneShotExit.MODE_REFUSED.code
        AdminDbAccounts.root(container).use { (it.scalar("SELECT COUNT(*) FROM admin_account") as Number).toInt() } shouldBe 0
    }
})
