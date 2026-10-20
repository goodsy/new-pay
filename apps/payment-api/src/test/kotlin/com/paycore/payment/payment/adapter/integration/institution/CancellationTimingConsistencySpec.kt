package com.paycore.payment.payment.adapter.integration.institution

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * 취소 시간 설정 기동 검사(CANCEL-001 §6.6·CN-29 — D-S4-2를 S7에서): 모든 sim-van-2 Route의 취소 Capability에 대해 `cancel.closable-after ≥ preparedDeadline + dispatchLease`(전송이 끝났을 수
 * 있는 시간 뒤에만 종결), 회복 `max-age < cancel.inquiry-retention`(보존기간 안에서 이관), `connectTimeout × (예산 + 1) < lease − margin`(lease 안에 첫 송신). 어긋나면 기동 실패. 값은 Demo.
 */
class CancellationTimingConsistencySpec : BehaviorSpec({

    fun runner(vararg props: Pair<String, String>) = ApplicationContextRunner()
        .withUserConfiguration(
            InstitutionRouteRegistryConfiguration::class.java, CancellationTimingConfiguration::class.java, CancellationTimingConsistency::class.java,
        )
        .withPropertyValues(
            *(
                mapOf(
                    "paycore.authorization.backends.TEST.type" to "SIMULATOR",
                    "paycore.authorization.routes.SIM-VAN-C.environment" to "TEST",
                    "paycore.authorization.routes.SIM-VAN-C.institution" to "SIMVAN",
                    "paycore.authorization.routes.SIM-VAN-C.protocol-version" to "sim-van-2",
                    "paycore.authorization.routes.SIM-VAN-C.profile-version" to "sim-van-c-demo-1",
                    "paycore.authorization.routes.SIM-VAN-C.host" to "127.0.0.1",
                    "paycore.authorization.routes.SIM-VAN-C.port" to "17001",
                    "paycore.authorization.routes.SIM-VAN-C.key-id" to "sim-van-test-1",
                    "paycore.authorization.routes.SIM-VAN-C.key-base64" to "dGVzdC1vbmx5LXNpbS12YW4taG1hYy1rZXktMDAwMDE=",
                    "paycore.authorization.routes.SIM-VAN-C.connect-timeout" to "PT0.5S",
                    "paycore.authorization.routes.SIM-VAN-C.read-timeout" to "PT1S",
                    "paycore.authorization.routes.SIM-VAN-C.total-deadline" to "PT2S",
                    "paycore.authorization.routes.SIM-VAN-C.connect-retry-budget" to "2",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.confirms-no-effect" to "true",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.closable-after" to "PT2M",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.inquiry-retention" to "P7D",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.same-id-resend-safe" to "true",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.authentication-evidence-reusable-for-resend" to "true",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.supported-currencies" to "KRW",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.supported-card-types" to "CREDIT,CHECK",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.installment-card-types" to "CREDIT",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.installment-months" to "2,3,6",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.cancel.version" to "cancel-c-demo-1",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.cancel.partial-lump-sum-card-types" to "CREDIT",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.cancel.partial-installment-card-types" to "CREDIT",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.cancel.confirms-no-effect" to "true",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.cancel.closable-after" to "PT2M",
                    "paycore.authorization.routes.SIM-VAN-C.capabilities.cancel.inquiry-retention" to "P7D",
                    "paycore.cancellation.timing.dispatch.lease" to "PT30S",
                    "paycore.cancellation.timing.dispatch.safety-margin" to "PT5S",
                    "paycore.cancellation.timing.dispatch.prepared-deadline" to "PT1S",
                    "paycore.cancellation.timing.dispatch.gate-retry-interval" to "PT0.2S",
                    "paycore.cancellation.timing.recovery.enabled" to "false",
                    "paycore.cancellation.timing.recovery.fixed-delay" to "PT5S",
                    "paycore.cancellation.timing.recovery.batch-size" to "50",
                    "paycore.cancellation.timing.recovery.inquiry-interval" to "PT10S",
                    "paycore.cancellation.timing.recovery.inquiry-budget" to "3",
                    "paycore.cancellation.timing.recovery.max-age" to "PT1H",
                    "paycore.cancellation.timing.recovery.slow-inquiry-interval" to "PT10M",
                    "paycore.cancellation.timing.recovery.version" to "cancel-recovery-demo-1",
                ) + props
                ).map { (k, v) -> "$k=$v" }.toTypedArray(),
        )

    Given("cancel timing settings and sim-van-2 routes") {
        Then("the Demo values start") {
            runner().run { withClue(it.startupFailure) { it.startupFailure shouldBe null } }
        }
        Then("closable-after shorter than prepared deadline + lease, max-age not shorter than the cancel retention, or a connect budget beyond the lease is refused") {
            mapOf(
                "closable after too short" to arrayOf("paycore.authorization.routes.SIM-VAN-C.capabilities.cancel.closable-after" to "PT30S"),
                "max age reaches retention" to arrayOf(
                    "paycore.cancellation.timing.recovery.max-age" to "P8D", "paycore.cancellation.timing.recovery.slow-inquiry-interval" to "PT10M",
                ),
                "connect budget beyond lease" to arrayOf("paycore.authorization.routes.SIM-VAN-C.connect-timeout" to "PT9S"),
            ).forEach { (name, props) -> withClue(name) { runner(*props).run { it.startupFailure shouldNotBe null } } }
        }
    }
})
