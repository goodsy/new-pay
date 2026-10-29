package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.CapturedLogs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.util.Base64

/**
 * ADMIN 채널 기동 경로 비노출(ADMIN-PAYMENT-OPS-001 §6, checkpoint L-3 — WEBHOOK `MerchantWebhookStartupDisclosureSpec` 방식): admin-channel 설정이 잘못되어 기동이 실패해도 Boot 실패
 * 보고(`FailureAnalyzer` "Value:" 줄 포함)·예외 사슬 어디에도 비밀(Base64 원문)이 나오지 않는다. 시험 비밀은 canary(운영 비밀 아님). 실제 실패 보고를 수집했는지 먼저 확인한다.
 */
class AdminChannelNonDisclosureSpec : BehaviorSpec({

    val canary = Base64.getEncoder().encodeToString("LEAKCANARY-admin-channel-0123456789-abcdef".toByteArray())
    val canary2 = Base64.getEncoder().encodeToString("LEAKCANARY-admin-channel-9876543210-fedcba".toByteArray())

    fun failedStartupLines(vararg args: String): List<String> {
        val previous = System.getProperty(LOGGING_SYSTEM)
        System.setProperty(LOGGING_SYSTEM, "none")
        try {
            CapturedLogs().use { logs ->
                val failure = shouldThrow<Exception> {
                    SpringApplicationBuilder(AdminChannelConfiguration::class.java).web(WebApplicationType.NONE).logStartupInfo(false).run(*args).close()
                }
                val chain = generateSequence(failure as Throwable) { it.cause?.takeIf { c -> c !== it } }.joinToString(" | ") { "${it::class.java.name}: ${it.message}" }
                return logs.lines() + chain
            }
        } finally {
            if (previous == null) System.clearProperty(LOGGING_SYSTEM) else System.setProperty(LOGGING_SYSTEM, previous)
        }
    }

    Given("a duplicate key id with two canary secrets") {
        val lines = failedStartupLines(
            "--paycore.payment.admin-channel.clock-window=PT300S",
            "--paycore.payment.admin-channel.signing-keys[0].key-id=adm-k1", "--paycore.payment.admin-channel.signing-keys[0].secret=$canary",
            "--paycore.payment.admin-channel.signing-keys[1].key-id=adm-k1", "--paycore.payment.admin-channel.signing-keys[1].secret=$canary2",
        )
        Then("startup fails and neither the failure report nor the exception chain contains a secret") {
            lines.shouldNotBeEmpty()
            lines.any { it.contains("APPLICATION FAILED TO START") } shouldBe true
            lines.forEach {
                it shouldNotContain canary
                it shouldNotContain canary2
            }
        }
    }

    Given("a clock window out of range next to a canary secret (the last bound value would be the secret)") {
        val lines = failedStartupLines(
            "--paycore.payment.admin-channel.signing-keys[0].key-id=adm-k1", "--paycore.payment.admin-channel.signing-keys[0].secret=$canary",
            "--paycore.payment.admin-channel.clock-window=PT11M",
        )
        Then("startup fails without printing the secret") {
            lines.shouldNotBeEmpty()
            lines.any { it.contains("APPLICATION FAILED TO START") } shouldBe true
            lines.forEach { it shouldNotContain canary }
        }
    }
}) {
    private companion object {
        const val LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem"
    }
}
