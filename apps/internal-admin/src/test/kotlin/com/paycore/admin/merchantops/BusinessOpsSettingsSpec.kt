package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.adapter.integration.BusinessOpsSettings
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * BUSINESS-PROFILE-001 BIZ-3 리뷰 MINOR-2(§7.1 m-2 "설정 키 누락·0 → 기동 실패"): 실제 Spring 바인딩(`@ConfigurationProperties("paycore.admin.merchant-ops")`)으로
 * `business-reads-per-minute`가 없거나 0·상한 초과면 컨텍스트 기동이 실패하고(실패 메시지에 키 이름), Demo 값 30은 기동·바인딩된다(대조). 같은 접두어의 `approval` 블록은 무시한다.
 */
class BusinessOpsSettingsSpec : BehaviorSpec({

    fun runner() = ApplicationContextRunner().withUserConfiguration(BusinessOpsSettingsTestConfiguration::class.java)
    fun failure(vararg properties: String): String? {
        var messages: String? = null
        runner().withPropertyValues(*properties).run { context ->
            messages = context.startupFailure?.let { f -> generateSequence<Throwable>(f) { it.cause }.joinToString(" | ") { it.message.orEmpty() } }
        }
        return messages
    }

    Given("MINOR-2 business-reads-per-minute through real Spring binding") {
        Then("a missing key fails context startup naming the key") {
            val messages = failure("paycore.admin.merchant-ops.approval.execution-window=1h")
            messages shouldNotBe null
            messages!! shouldContain "paycore.admin.merchant-ops.business-reads-per-minute is required"
        }
        Then("0 and 10001 fail context startup naming the range") {
            failure("paycore.admin.merchant-ops.business-reads-per-minute=0")!! shouldContain "paycore.admin.merchant-ops.business-reads-per-minute 1..10000"
            failure("paycore.admin.merchant-ops.business-reads-per-minute=10001")!! shouldContain "paycore.admin.merchant-ops.business-reads-per-minute 1..10000"
        }
        Then("control: the Demo value 30 starts and binds (the approval block of the same prefix is ignored)") {
            runner().withPropertyValues("paycore.admin.merchant-ops.business-reads-per-minute=30", "paycore.admin.merchant-ops.approval.execution-window=1h").run { context ->
                context.startupFailure shouldBe null
                context.getBean(BusinessOpsSettings::class.java).businessReadsPerMinute shouldBe 30
            }
        }
    }
})

/** @TestConfiguration — 앱 컴포넌트 스캔에서 빠진다(다른 @SpringBootTest 컨텍스트에 끼지 않음). */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(BusinessOpsSettings::class)
class BusinessOpsSettingsTestConfiguration
