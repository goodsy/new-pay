package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.InternalConnector
import com.paycore.payment.payment.adapter.web.PaymentApiSettings
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.coyote.AbstractProtocol
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 시험용: PaymentApiSettings 바인딩만 켠다. */
@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentApiSettings::class)
class AdminConnectorBudgetApiSettingsConfig

/**
 * 내부 connector 상한(ADMIN-PAYMENT-OPS-001 §4.7 F-8, review L-2): 기동 뒤 실제 내부 connector의 protocol handler `maxThreads`·`acceptCount`가 설정값(시험 16·16)이고, 관리 permit 합이
 * 내부 작업 스레드 상한보다 작지 않으면(`readPermits + commandPermits < internalMaxThreads` 위반) 기동을 거부한다.
 */
@PaymentApiTest
class AdminChannelConnectorBudgetSpec(private val internal: InternalConnector) : BehaviorSpec({

    Given("the running internal connector") {
        Then("its protocol handler uses the configured maxThreads and acceptCount") {
            val protocol = internal.connector.protocolHandler as AbstractProtocol<*>
            protocol.maxThreads shouldBe 16
            protocol.acceptCount shouldBe 16
        }
    }

    Given("admin permits against the internal thread limit") {
        fun runner(maxThreads: Int) = ApplicationContextRunner()
            .withUserConfiguration(AdminConnectorBudgetApiSettingsConfig::class.java, AdminOpsConfiguration::class.java)
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withPropertyValues(
                "paycore.payment.api.internal-port=0", "paycore.payment.api.internal-max-threads=$maxThreads", "paycore.payment.api.internal-accept-count=16",
                "paycore.payment.api.retry-after.eligibility-unavailable=PT2S", "paycore.payment.api.retry-after.temporarily-unavailable=PT1S",
                "paycore.payment.api.retry-after.request-in-progress=PT1S",
                "paycore.payment.admin-ops.read-permits=4", "paycore.payment.admin-ops.command-permits=2", "paycore.payment.admin-ops.public-pool-reserve=4",
            )
        Then("4 + 2 permits start with 7 threads and refuse with 6") {
            runner(7).run { it.startupFailure shouldBe null }
            runner(6).run { it.startupFailure.shouldNotBeNull() }
        }
    }
})
