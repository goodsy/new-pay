package com.paycore.master.merchant.adapter.batch

import com.paycore.master.merchant.application.provided.DemoMerchantSeedCommand
import com.paycore.master.merchant.application.provided.DemoMerchantSeedResult
import com.paycore.master.merchant.application.provided.DemoMerchantSeedUseCase
import com.paycore.master.merchant.domain.SeoulMidnight
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * `demo-seed` argv 해석·출력(DEMO-SEED-001 명세 §4.7·§4.8). 순서: 명령·`--effective-from` 검사 → **출력 준비(DB 변경 전)** → Use Case(단계별 로컬 TX)
 * → 성공이면 출력, 아니면 준비한 빈 파일 삭제. 배치 모드(DEMO) 판정은 이 앞(환경 단계 게이트·runner)에서 끝났다.
 *
 * 인자: `--output=tty|file:<path> --effective-from=<ISO-8601 offset 시각, Asia/Seoul 00:00>`. 로그는 고정 code만(값·참조 없음).
 */
@Component
@ConditionalOnProperty(prefix = "paycore.master", name = ["command"])
class DemoSeedCommandLine(private val seed: ObjectProvider<DemoMerchantSeedUseCase>, private val console: ConsoleAccess) {

    fun run(command: String?, options: Map<String, String>): Int {
        val correlationId = "demo-seed-" + UUID.randomUUID().toString().take(CORRELATION_SUFFIX)
        if (command != COMMAND) return finish(DemoSeedExit.USAGE, "COMMAND", correlationId)
        val effectiveFrom = parseEffectiveFrom(options["effective-from"]) ?: return finish(DemoSeedExit.USAGE, "EFFECTIVE_FROM_FORMAT", correlationId)
        val output = when (val prepared = DemoSeedOutput.prepare(options["output"], console)) {
            is DemoSeedOutput.Prepared.Ready -> prepared.output
            is DemoSeedOutput.Prepared.Refused -> return finish(DemoSeedExit.OUTPUT_REFUSED, prepared.reason, correlationId)
            DemoSeedOutput.Prepared.Invalid -> return finish(DemoSeedExit.USAGE, "OUTPUT", correlationId)
        }
        val result = try {
            seed.getObject().seed(
                DemoMerchantSeedCommand(DemoMerchantSeedValues.KEY_PREFIX, DemoMerchantSeedValues.ACTOR, correlationId, effectiveFrom, DemoMerchantSeedValues.VALUES),
            )
        } catch (e: RuntimeException) {
            output.discard()
            return finish(DemoSeedExit.FAILED, e::class.simpleName ?: "RuntimeException", correlationId)
        }
        if (result !is DemoMerchantSeedResult.Done) {
            output.discard()
            return when (result) {
                is DemoMerchantSeedResult.EffectiveFromRefused -> finish(DemoSeedExit.CONDITION_REFUSED, result.reason.name, correlationId)
                is DemoMerchantSeedResult.StepRejected -> finish(DemoSeedExit.STEP_REJECTED, result.rejection.name, correlationId, result.step.number)
                is DemoMerchantSeedResult.ContentConflict -> finish(DemoSeedExit.CONTENT_CONFLICT, "IDEMPOTENCY_KEY_REUSED", correlationId, result.step.number)
                is DemoMerchantSeedResult.RetryLater -> finish(DemoSeedExit.RETRY_LATER, "TECHNICAL_FAILURE", correlationId, result.step.number)
                is DemoMerchantSeedResult.Done -> error("handled above")
            }
        }
        return try {
            output.write(
                DemoMerchantSeedValues.OUTPUT_HEADER,
                listOf(
                    "merchantId" to result.merchantId.value,
                    "merchantAccountId" to result.merchantAccountId.value,
                    "siteId" to result.siteId.value,
                    "siteHost" to DemoMerchantSeedValues.SITE_HOST,
                    "effectiveFrom" to result.effectiveFrom.toString(),
                    "replayed" to result.replayed.toString(),
                ),
            )
            finish(DemoSeedExit.SUCCESS, if (result.replayed) "REPLAYED" else "SEEDED", correlationId)
        } catch (_: IOException) {
            // 커밋 뒤 출력 실패: 만든 파일은 지웠다(DemoSeedOutput). 같은 명령을 다시 실행하면 재생 결과가 다시 출력된다(비밀 없음).
            System.err.println("demo-seed: the seed was committed but writing the output failed; the output file was removed. Re-run the same command to print it again.")
            finish(DemoSeedExit.OUTPUT_FAILED, "OUTPUT_WRITE", correlationId)
        }
    }

    private fun finish(exit: DemoSeedExit, reason: String, correlationId: String, step: Int? = null): Int {
        log.info("operation=demo-seed result={} step={} reason={} correlationId={}", exit.name, step?.toString()?.padStart(2, '0') ?: "-", reason, correlationId)
        return exit.code
    }

    companion object {
        const val COMMAND = "demo-seed"
        private const val CORRELATION_SUFFIX = 13
        private val log = LoggerFactory.getLogger(DemoSeedCommandLine::class.java)

        /** ISO-8601 offset 시각, Asia/Seoul 00:00(ms 0)만. 아니면 null(USAGE). */
        fun parseEffectiveFrom(value: String?): Instant? {
            val instant = try {
                OffsetDateTime.parse(value ?: return null).toInstant()
            } catch (_: DateTimeParseException) {
                return null
            }
            return instant.takeIf { SeoulMidnight.isBoundary(it) }
        }
    }
}
