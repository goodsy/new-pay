package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryTimings
import com.paycore.payment.payment.domain.WebhookRetrySchedule
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 기동 검사(검토 m-4 — `CancellationTimingConsistency` 방식): claim lease > DNS 예산 + 전체 요청 기한 + 여유(5초). 정상 시도 중에 lease가 끝나 다른 worker가 같은 전달을 다시 잡는 일은
 * 비정상 정지(GC·프로세스 정지) 때만 생긴다(그때 중복 전송은 at-least-once 계약 안 — 가맹점 eventId 중복 제거).
 */
object MerchantWebhookTimingConsistency {
    private val MARGIN: Duration = Duration.ofSeconds(5)

    /** 한 시도 최대 시간 = DNS 예산 + 전체 요청 기한 + 여유. */
    fun attemptBudget(transport: MerchantWebhookTransportSettings): Duration = transport.dnsTimeout.plus(transport.requestDeadline).plus(MARGIN)

    fun check(delivery: MerchantWebhookDeliverySettings, transport: MerchantWebhookTransportSettings) {
        check(delivery.claimLease > attemptBudget(transport)) { "paycore.webhook.delivery.claim-lease must exceed dns-timeout + request-deadline + ${MARGIN.seconds}s" }
    }
}

/**
 * [MerchantWebhookDeliveryTimings] 구현: delivery 설정(일정·lease) + 전송 설정에서 계산한 한 시도 예산. 만들 때 [MerchantWebhookTimingConsistency]를 실행한다(실패 = 기동 실패).
 */
@Component
class ConfiguredMerchantWebhookDeliveryTimings(delivery: MerchantWebhookDeliverySettings, transport: MerchantWebhookTransportSettings) : MerchantWebhookDeliveryTimings {
    override val schedule: WebhookRetrySchedule = delivery.schedule
    override val claimLease: Duration = delivery.claimLease
    override val attemptBudget: Duration = MerchantWebhookTimingConsistency.attemptBudget(transport)

    init {
        MerchantWebhookTimingConsistency.check(delivery, transport)
    }
}
