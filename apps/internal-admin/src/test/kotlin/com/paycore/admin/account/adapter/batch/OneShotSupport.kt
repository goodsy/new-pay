package com.paycore.admin.account.adapter.batch

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.EnrollmentResult
import com.paycore.admin.account.application.provided.EnrollmentUseCase
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.update
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer
import java.io.StringWriter
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 1회성 명령 시험 지원(S6 — 시험 전용). 이 시험들은 전용 컨텍스트(전용 컨테이너)에서 돌고, 시험마다 ADMIN 업무 표를 root로 비운다(빈 표·0명 조건 시험). */
object OneShotSupport {
    private val TABLES = listOf(
        "admin_role_event", "admin_enrollment_code", "admin_approval_request", "admin_audit_event", "admin_bootstrap", "admin_recovery_episode", "admin_account",
    )

    fun wipe(container: MySQLContainer) = AdminDbAccounts.root(container).use { c ->
        c.update("SET FOREIGN_KEY_CHECKS = 0")
        TABLES.forEach { c.update("DELETE FROM $it") }
        c.update("SET FOREIGN_KEY_CHECKS = 1")
    }

    fun context() = AdminRequestContext("corr-" + UUID.randomUUID().toString().take(8), "10.0.0.7")

    /** 시험 대역 TTY(`System.console()` 대신). [terminal]이 거짓이면 콘솔 없음. */
    class FakeConsole(private val terminal: Boolean) : ConsoleAccess {
        val written = StringWriter()

        override fun terminalWriter(): Writer? = if (terminal) written else null
    }

    fun outputFile(): Path = Files.createTempDirectory("paycore-oneshot-").resolve("codes.txt")

    /** 출력 줄 `<login>\t<purpose>\t<code>` → login → code. */
    fun codesIn(text: String): Map<String, String> =
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line -> line.split('\t').let { it[0] to it[2] } }

    /** 코드로 등록 완료(비밀번호 + TOTP — ENROLL·RECOVERY). */
    fun enroll(enrollment: EnrollmentUseCase, actorId: String, code: String) {
        var draft = enrollment.begin(code).shouldBeInstanceOf<EnrollmentResult.InProgress>().draft
        draft = enrollment.providePassword(draft, "one-shot-password-1".toCharArray()).shouldBeInstanceOf<EnrollmentResult.InProgress>().draft
        val provisioning = enrollment.startTotp(draft).shouldBeInstanceOf<EnrollmentResult.TotpProvisioning>()
        enrollment.complete(provisioning.draft, TestTotp.code(TestTotp.secretOf(provisioning.otpauthUri)), context()) shouldBe EnrollmentResult.Completed(actorId)
    }
}
