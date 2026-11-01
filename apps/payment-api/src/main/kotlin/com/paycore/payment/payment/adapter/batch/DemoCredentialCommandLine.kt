package com.paycore.payment.payment.adapter.batch

import com.paycore.payment.payment.application.provided.DemoCredentialIssueCommand
import com.paycore.payment.payment.application.provided.DemoCredentialIssueResult
import com.paycore.payment.payment.application.provided.DemoCredentialIssueUseCase
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.UUID

/**
 * `demo-credential` argv 해석·출력(DEMO-SEED-001 명세 §4.5·§4.7·§4.8). 순서: 명령·`--merchant-account-id` 검사 → **출력 준비(DB 변경 전)** → Use Case(한 PAYMENT
 * 로컬 TX) → 커밋됐으면 같은 채널에 원문 출력, 아니면 준비한 빈 파일 삭제. 배치 모드(DEMO) 판정은 이 앞(환경 단계 게이트·runner)에서 끝났다.
 *
 * 인자: `--output=tty|file:<path> --merchant-account-id=<MID>`. 로그는 고정 code만(키·keyId·secret·MID 없음).
 */
@Component
@ConditionalOnProperty(prefix = "paycore.payment", name = ["command"])
class DemoCredentialCommandLine(private val issuer: ObjectProvider<DemoCredentialIssueUseCase>, private val console: ConsoleAccess) {

    fun run(command: String?, options: Map<String, String>): Int {
        val correlationId = "demo-credential-" + UUID.randomUUID().toString().take(CORRELATION_SUFFIX)
        if (command != COMMAND) return finish(DemoCredentialExit.USAGE, "COMMAND", correlationId)
        val mid = options["merchant-account-id"]?.takeIf { DemoCredentialIssueCommand.MID_FORMAT.matches(it) }
            ?: return finish(DemoCredentialExit.USAGE, "MERCHANT_ACCOUNT_ID_FORMAT", correlationId)
        val output = when (val prepared = DemoCredentialOutput.prepare(options["output"], console)) {
            is DemoCredentialOutput.Prepared.Ready -> prepared.output
            is DemoCredentialOutput.Prepared.Refused -> return finish(DemoCredentialExit.OUTPUT_REFUSED, prepared.reason, correlationId)
            DemoCredentialOutput.Prepared.Invalid -> return finish(DemoCredentialExit.USAGE, "OUTPUT", correlationId)
        }
        val result = try {
            issuer.getObject().issue(DemoCredentialIssueCommand(mid, correlationId))
        } catch (e: RuntimeException) {
            output.discard()
            return finish(DemoCredentialExit.FAILED, e::class.simpleName ?: "RuntimeException", correlationId)
        }
        val issued = when (result) {
            is DemoCredentialIssueResult.Issued -> result
            is DemoCredentialIssueResult.Refused -> {
                output.discard()
                return finish(DemoCredentialExit.CONDITION_REFUSED, result.reason.name, correlationId)
            }
            DemoCredentialIssueResult.Lost -> {
                output.discard()
                return finish(DemoCredentialExit.LOST, "ISSUANCE_RACE_LOST", correlationId)
            }
            DemoCredentialIssueResult.KeyIdCollision -> {
                output.discard()
                return finish(DemoCredentialExit.KEY_ID_COLLISION, "KEY_ID_COLLISION", correlationId)
            }
        }
        return try {
            output.write(
                OUTPUT_HEADER,
                listOf(
                    "merchantAccountId" to issued.merchantAccountId,
                    "environment" to "TEST",
                    "keyId" to issued.keyId,
                    "apiKey" to issued.apiKey,
                    "validUntil" to issued.validUntil.toString(),
                ),
            )
            finish(DemoCredentialExit.SUCCESS, "ISSUED", correlationId)
        } catch (_: IOException) {
            // 커밋 뒤 출력 실패: 만든 파일은 지웠다(DemoCredentialOutput). 원문은 다시 보이지 않고 재발급도 없다(D-DEMO-004).
            System.err.println(
                "demo-credential: the key was committed but writing the output failed; the output file was removed. " +
                    "The key cannot be shown again (no reissue): reset the Demo database and seed again.",
            )
            finish(DemoCredentialExit.OUTPUT_FAILED, "OUTPUT_WRITE", correlationId)
        }
    }

    private fun finish(exit: DemoCredentialExit, reason: String, correlationId: String): Int {
        log.info("operation=demo-credential result={} reason={} correlationId={}", exit.name, reason, correlationId)
        return exit.code
    }

    companion object {
        const val COMMAND = "demo-credential"
        private const val CORRELATION_SUFFIX = 13
        private val log = LoggerFactory.getLogger(DemoCredentialCommandLine::class.java)

        const val OUTPUT_HEADER =
            "PayCore DEMO API key — NOT OPERATIONAL (environment TEST, DEMO deployment only, valid 7 days, no reissue). Use locally, then delete this file."
    }
}
