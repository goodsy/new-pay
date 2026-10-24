package com.paycore.simulator.merchant.adapter.persistence

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository

/** 가상 가맹점 수신 원장 Spring Data repository(adapter.persistence 전용). */
interface MerchantWebhookReceiptJpaRepository : JpaRepository<JpaMerchantWebhookReceiptEntity, Long> {
    fun countByReceiverIdAndEventIdAndOutcomeIn(receiverId: String, eventId: String, outcomes: Collection<String>): Long

    fun existsByReceiverIdAndFirstAcceptanceEventId(receiverId: String, firstAcceptanceEventId: String): Boolean

    fun findByReceiverIdOrderByReceiptIdAsc(receiverId: String, limit: Limit): List<JpaMerchantWebhookReceiptEntity>
}
