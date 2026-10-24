package com.paycore.archfixture.webhook

import com.paycore.payment.payment.application.required.MerchantWebhookEventRecord
import com.paycore.payment.payment.application.required.MerchantWebhookEventStore

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 업무 서비스가 가맹점 Webhook Port를 직접 쓴다 — 업무 TX는 Webhook 경로와 독립이어야 한다(WEBHOOK-001 WH-02·n-6, D-WH-006 "전달 실패는 금융 효과 없음").
 */
class CancelServiceTouchingWebhook(private val events: MerchantWebhookEventStore) {
    fun cancelAndNotify(record: MerchantWebhookEventRecord) = events.append(record)
}
