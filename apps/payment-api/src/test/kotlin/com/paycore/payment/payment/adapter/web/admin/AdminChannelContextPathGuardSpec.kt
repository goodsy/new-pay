package com.paycore.payment.payment.adapter.web.admin

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * context path 금지(ADMIN-PAYMENT-OPS-001 §4.2 L-3, I-31): 서명 경로(servletPath + pathInfo) = 요청 URI 경로가 되도록 `server.servlet.context-path`가 비어 있지 않으면 PAYMENT 기동 거부.
 * 핵심 변이 "context path 검사 제거"를 잡는다.
 */
class AdminChannelContextPathGuardSpec : BehaviorSpec({

    val runner = ApplicationContextRunner().withUserConfiguration(AdminChannelConfiguration::class.java).withPropertyValues(
        "paycore.payment.admin-channel.clock-window=PT300S",
        "paycore.payment.admin-channel.signing-keys[0].key-id=${AdminChannelFixture.K1}",
        "paycore.payment.admin-channel.signing-keys[0].secret=${AdminChannelFixture.secret(AdminChannelFixture.K1)}",
    )

    Given("the servlet context path") {
        Then("absent or empty starts") {
            runner.run { it.startupFailure shouldBe null }
            runner.withPropertyValues("server.servlet.context-path=").run { it.startupFailure shouldBe null }
        }
        Then("any non-empty value refuses to start (including the root slash)") {
            listOf("/pay", "/internal", "/").forEach { cp ->
                runner.withPropertyValues("server.servlet.context-path=$cp").run { it.startupFailure.shouldNotBeNull() }
            }
        }
    }
})
