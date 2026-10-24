package com.paycore.payment.payment.adapter.integration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * delivery 설정(WEBHOOK-001 §5.5·§5.6, WH-17 — 기본값 없음, D-WH-006 Demo 값)과 기동 검사: claim lease는 한 시도의 최대 시간(DNS 예산 + 전체 요청 기한) + 여유(5초)보다 길어야 한다
 * (그래야 정상 시도 중에 다른 worker가 같은 전달을 다시 잡지 않는다 — lease 만료 재전송은 비정상 정지 때만).
 */
class MerchantWebhookDeliverySettingsSpec : BehaviorSpec({

    fun transport(deadline: Duration = Duration.ofSeconds(15), dns: Duration = Duration.ofSeconds(2)) = MerchantWebhookTransportSettings(
        connectTimeout = Duration.ofSeconds(3), responseTimeout = Duration.ofSeconds(10), requestDeadline = deadline, dnsTimeout = dns, maxResponseBytes = 4_096,
        testAllowlist = emptyList(), testTrustPem = null,
    )

    fun delivery(lease: Duration = Duration.ofSeconds(60)) = MerchantWebhookDeliverySettings(
        claimLease = lease,
        schedule = MerchantWebhookDeliverySettings.Schedule("webhook-retry-demo-1", Duration.ofSeconds(10), Duration.ofHours(1), 0.2, 10, Duration.ofHours(24)),
    )

    Given("the Demo values (D-WH-006)") {
        Then("they bind into the domain schedule unchanged") {
            val s = delivery().schedule
            listOf(s.version, s.initial, s.max, s.jitterRatio, s.maxAttempts, s.maxAge) shouldBe
                listOf("webhook-retry-demo-1", Duration.ofSeconds(10), Duration.ofHours(1), 0.2, 10, Duration.ofHours(24))
        }
        Then("a 60s lease covers 2s DNS + 15s deadline + 5s margin") {
            MerchantWebhookTimingConsistency.check(delivery(), transport())
        }
    }

    Given("a lease that does not cover one attempt") {
        Then("startup is refused at the boundary (lease must exceed dns + deadline + margin)") {
            shouldThrow<IllegalStateException> { MerchantWebhookTimingConsistency.check(delivery(Duration.ofSeconds(22)), transport()) }
            MerchantWebhookTimingConsistency.check(delivery(Duration.ofSeconds(23)), transport())
        }
    }

    Given("invalid schedule values") {
        Then("they are rejected at binding (the domain schedule validates)") {
            shouldThrow<IllegalArgumentException> { MerchantWebhookDeliverySettings.Schedule("", Duration.ofSeconds(10), Duration.ofHours(1), 0.2, 10, Duration.ofHours(24)).toDomain() }
            shouldThrow<IllegalArgumentException> { delivery(Duration.ZERO) }
        }
    }
})
