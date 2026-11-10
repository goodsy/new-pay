package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.domain.ApprovalWindow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import java.time.Duration

/**
 * MERCHANT-OPS-002 §9.3 판정 2 설정(C-19): `paycore.master.admin-channel.approval.max-execution-window`·`max-clock-skew`는 **기본값이 없다** — 빠지면 서블릿 웹 컨텍스트가 기동하지 않는다.
 * 범위 밖도 기동하지 않는다. 비웹 1회성 명령(demo-seed)에는 이 설정·bean이 없다(근거 없는 seed 경로는 창을 쓰지 않는다).
 */
class AdminApprovalSettingsSpec : BehaviorSpec({
    val web = WebApplicationContextRunner().withUserConfiguration(AdminApprovalConfiguration::class.java)
    val demo = arrayOf("paycore.master.admin-channel.approval.max-execution-window=PT24H", "paycore.master.admin-channel.approval.max-clock-skew=PT1M")

    Given("the approval window settings") {
        Then("the Demo values start a servlet web context and give the window used in commands") {
            web.withPropertyValues(*demo).run {
                it.startupFailure shouldBe null
                it.getBean(AdminApprovalSettings::class.java).window shouldBe ApprovalWindow(Duration.ofHours(24), Duration.ofMinutes(1))
            }
        }

        Then("a missing value (either one, or both) does not start (no default)") {
            web.run { it.startupFailure.shouldNotBeNull() }
            web.withPropertyValues(demo[0]).run { it.startupFailure.shouldNotBeNull() }
            web.withPropertyValues(demo[1]).run { it.startupFailure.shouldNotBeNull() }
        }

        Then("out of range values do not start (zero or over 7 days window, negative or over 10 minutes skew)") {
            listOf(
                arrayOf("paycore.master.admin-channel.approval.max-execution-window=PT0S", demo[1]),
                arrayOf("paycore.master.admin-channel.approval.max-execution-window=P8D", demo[1]),
                arrayOf(demo[0], "paycore.master.admin-channel.approval.max-clock-skew=-PT1S"),
                arrayOf(demo[0], "paycore.master.admin-channel.approval.max-clock-skew=PT11M"),
            ).forEach { values -> web.withPropertyValues(*values).run { it.startupFailure.shouldNotBeNull() } }
        }

        Then("a non-web context needs no approval settings and has no bean") {
            ApplicationContextRunner().withUserConfiguration(AdminApprovalConfiguration::class.java).run {
                it.startupFailure shouldBe null
                it.getBeansOfType(AdminApprovalSettings::class.java).size shouldBe 0
            }
        }
    }
})
