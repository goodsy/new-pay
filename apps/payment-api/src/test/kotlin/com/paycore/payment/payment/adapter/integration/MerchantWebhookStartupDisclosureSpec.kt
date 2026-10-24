package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.adapter.web.CapturedLogs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.io.ClassPathResource

/**
 * T-N1(WEBHOOK-001 §11, 검토 #2 n-5) — 기동 경로 비노출:
 * - 서명 비밀 설정이 잘못되어 기동이 실패해도 실패 보고(Boot `FailureAnalyzer` "Value:" 줄 포함)·예외 사슬 어디에도 비밀 원문이 나오지 않는다.
 * - 운영 설정(main `application.yml`)이 HttpClient wire·header 로거를 WARN으로 고정한다(DEBUG면 서명 헤더·본문이 그대로 찍힌다).
 * 시험 비밀은 의도적으로 문법이 틀린 canary 문자열이다(운영 비밀 아님).
 */
class MerchantWebhookStartupDisclosureSpec : BehaviorSpec({

    val canary = "LEAKCANARY0123456789"

    fun failedStartupLines(vararg args: String): List<String> {
        val previous = System.getProperty(LOGGING_SYSTEM)
        System.setProperty(LOGGING_SYSTEM, "none") // Boot가 logback을 다시 초기화해 수집 appender를 떼지 않게 한다.
        try {
            CapturedLogs().use { logs ->
                val failure = shouldThrow<Exception> {
                    SpringApplicationBuilder(MerchantWebhookSettingsConfiguration::class.java).web(WebApplicationType.NONE).logStartupInfo(false).run(*args).close()
                }
                val chain = generateSequence(failure as Throwable) { it.cause?.takeIf { c -> c !== it } }.joinToString(" | ") { "${it::class.java.name}: ${it.message}" }
                return logs.lines() + chain
            }
        } finally {
            if (previous == null) System.clearProperty(LOGGING_SYSTEM) else System.setProperty(LOGGING_SYSTEM, previous)
        }
    }

    Given("a malformed TEST signing secret") {
        val lines = failedStartupLines(
            "--paycore.webhook.test-endpoints=",
            "--paycore.webhook.test-signing-keys[0].merchant-account-id=mid_01JB000000000000000000TN1A",
            "--paycore.webhook.test-signing-keys[0].key-id=whk_t_n1",
            "--paycore.webhook.test-signing-keys[0].secret=whsec_$canary",
        )
        Then("startup fails, and neither the failure report nor the exception chain contains the secret") {
            lines.shouldNotBeEmpty()
            // 시험이 실제 실패 보고(FailureAnalyzer 출력 — "Value:" 줄이 있는 곳)를 수집했음을 먼저 확인한다(빈 수집으로 통과하지 않게).
            lines.any { it.contains("APPLICATION FAILED TO START") } shouldBe true
            lines.forEach { it shouldNotContain canary }
        }
    }

    Given("a valid secret next to an invalid sibling setting (the last bound value would be the secret)") {
        val lines = failedStartupLines(
            "--paycore.webhook.test-endpoints=",
            "--paycore.webhook.test-signing-keys[0].merchant-account-id=mid_01JB000000000000000000TN1A",
            "--paycore.webhook.test-signing-keys[0].key-id=whk_t_n1",
            "--paycore.webhook.test-signing-keys[0].secret=whsec_${canary}abcdefghijklmnopqrstuvw",
            "--paycore.webhook.test-signing-keys[1].merchant-account-id=mid_01JB000000000000000000TN1A",
            "--paycore.webhook.test-signing-keys[1].key-id=whk_t_n1",
            "--paycore.webhook.test-signing-keys[1].secret=whsec_${canary}zyxwvutsrqponmlkjihgfedc",
        )
        Then("the duplicate key id fails startup without printing either secret") {
            lines.shouldNotBeEmpty()
            // 시험이 실제 실패 보고(FailureAnalyzer 출력 — "Value:" 줄이 있는 곳)를 수집했음을 먼저 확인한다(빈 수집으로 통과하지 않게).
            lines.any { it.contains("APPLICATION FAILED TO START") } shouldBe true
            lines.forEach { it shouldNotContain canary }
        }
    }

    Given("the operational logging configuration") {
        val yaml = YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }.getObject()!!
        Then("the HttpClient wire and header loggers are pinned at WARN") {
            yaml.getProperty("logging.level.org.apache.hc.client5.http.wire") shouldBe "WARN"
            yaml.getProperty("logging.level.org.apache.hc.client5.http.headers") shouldBe "WARN"
        }
    }
}) {
    private companion object {
        const val LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem"
    }
}
