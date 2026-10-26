package com.paycore.admin.account.adapter.batch

import com.paycore.admin.account.application.provided.BootstrapCommand
import com.paycore.admin.account.application.provided.NewAdminAccount
import com.paycore.admin.account.application.provided.OneShotCommand
import com.paycore.admin.account.application.provided.OneShotCommandUseCase
import com.paycore.admin.account.application.provided.OneShotResult
import com.paycore.admin.account.application.provided.RecoveryCommand
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.UUID

/**
 * 1회성 명령 인자 해석·출력(§4.9 — 명시 argv만). 순서: 인자 검사 → **출력 준비(DB 변경 전 — TTY 없음·파일 있음·권한 불일치면 거부, 거부 감사)** → Use Case(1 TX)
 * → 성공이면 출력 쓰기(실패면 재발급 경로 안내 비영 exit — 코드는 이미 커밋됨), 실패면 준비한 빈 파일을 지운다. 코드 원문은 출력 대상에만 쓴다(logger 없음).
 *
 * 인자: `bootstrap --output=tty|file:<path> --security-admin-login --security-admin-name --approver-login --approver-name`,
 * `recovery --output=… [--reissue=<login>[,<login>…]] [--new-security-admin-login --new-security-admin-name --new-approver-login --new-approver-name]`.
 */
@Component
class OneShotCommandLine(private val oneShot: OneShotCommandUseCase, private val console: ConsoleAccess) {

    fun run(command: String?, options: Map<String, String>): Int {
        val kind = when (command) {
            "bootstrap" -> OneShotCommand.BOOTSTRAP
            "recovery" -> OneShotCommand.RECOVERY
            else -> return OneShotExit.USAGE.code
        }
        val correlationId = "oneshot-" + UUID.randomUUID().toString().take(13)
        val request = parse(kind, options, correlationId) ?: return OneShotExit.USAGE.code
        val output = when (val prepared = CodeOutput.prepare(options["output"], console)) {
            is CodeOutput.Prepared.Ready -> prepared.output
            is CodeOutput.Prepared.Refused -> {
                oneShot.recordRefusal(kind, prepared.reason, correlationId)
                return OneShotExit.OUTPUT_REFUSED.code
            }
            CodeOutput.Prepared.Invalid -> return OneShotExit.USAGE.code
        }
        val result = try {
            request()
        } catch (e: RuntimeException) {
            output.discard()
            throw e
        }
        if (result !is OneShotResult.Done) {
            output.discard()
            return OneShotExit.of(result).code
        }
        return try {
            output.write(result.codes)
            OneShotExit.SUCCESS.code
        } catch (_: IOException) {
            // 커밋 뒤 출력 실패: 코드는 잃었다 — 재발급 경로(부트스트랩 불가면 복구 명령, 복구 회차는 만료 뒤 재실행)로 안내만 한다(코드 없음).
            // S6 리뷰 nit-2: 파일 상태를 함께 알린다(비었거나 일부만 쓰였거나 경로가 바뀌었을 수 있음 — 지우고 재발급).
            System.err.println(
                "one-shot: the codes were committed but writing the output failed; the output may be empty, partially written or replaced — delete it. " +
                    "No code is printed; use the re-issue path (recovery command).",
            )
            OneShotExit.OUTPUT_FAILED.code
        }
    }

    private fun parse(kind: OneShotCommand, o: Map<String, String>, correlationId: String): (() -> OneShotResult)? = when (kind) {
        OneShotCommand.BOOTSTRAP -> {
            val sa = account(o, "security-admin")
            val ap = account(o, "approver")
            if (sa == null || ap == null) null else BootstrapCommand(sa, ap, correlationId).let { c -> { oneShot.bootstrap(c) } }
        }
        OneShotCommand.RECOVERY -> {
            val reissue = o["reissue"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val pairKeys = listOf("new-security-admin", "new-approver").flatMap { listOf("$it-login", "$it-name") }
            val sa = account(o, "new-security-admin")
            val ap = account(o, "new-approver")
            when {
                pairKeys.none { it in o } -> RecoveryCommand(reissue, null, correlationId).let { c -> { oneShot.recover(c) } }
                sa == null || ap == null -> null
                else -> RecoveryCommand(reissue, sa to ap, correlationId).let { c -> { oneShot.recover(c) } }
            }
        }
    }

    private fun account(o: Map<String, String>, prefix: String): NewAdminAccount? {
        val login = o["$prefix-login"]?.takeIf { it.isNotBlank() } ?: return null
        val name = o["$prefix-name"]?.takeIf { it.isNotBlank() } ?: return null
        return NewAdminAccount(login, name)
    }
}
