package com.paycore.simulator.merchant.adapter.persistence

import com.paycore.simulator.merchant.application.required.DuplicateMerchantReceipt
import com.paycore.simulator.merchant.application.required.MerchantWebhookReceiptStore
import com.paycore.simulator.merchant.domain.MerchantWebhookReceipt
import com.paycore.simulator.merchant.domain.ReceiptOutcome
import com.paycore.simulator.merchant.domain.ReceivedEnvelope
import jakarta.persistence.EntityManager
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException

/** [MerchantWebhookReceiptStore] JPA 구현(MANDATORY TX, persist만 — 수정·삭제 연산 없음, `@Immutable` Entity, DB 권한 SELECT·INSERT). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantWebhookReceiptStore(
    private val rows: MerchantWebhookReceiptJpaRepository,
    private val entityManager: EntityManager,
) : MerchantWebhookReceiptStore {

    override fun append(receipt: MerchantWebhookReceipt) {
        val e = receipt.envelope
        val row = JpaMerchantWebhookReceiptEntity.newRow(
            receiverId = receipt.receiverId, outcome = receipt.outcome.name, eventId = e?.eventId, eventType = e?.type, eventSequence = e?.sequence,
            environment = e?.environment, keyId = receipt.keyId, bodySha256 = receipt.bodySha256, responseStatus = receipt.responseStatus,
            firstAcceptanceEventId = if (receipt.outcome == ReceiptOutcome.ACCEPTED) e?.eventId else null, receivedAt = receipt.receivedAt,
        )
        try {
            entityManager.persist(row)
            entityManager.flush()
        } catch (ex: RuntimeException) {
            if (isDuplicate(ex)) throw DuplicateMerchantReceipt()
            throw ex
        }
    }

    override fun countVerified(receiverId: String, eventId: String): Int =
        rows.countByReceiverIdAndEventIdAndOutcomeIn(receiverId, eventId, VERIFIED_DELIVERIES).toInt()

    override fun hasAccepted(receiverId: String, eventId: String): Boolean = rows.existsByReceiverIdAndFirstAcceptanceEventId(receiverId, eventId)

    override fun list(receiverId: String, limit: Int): List<MerchantWebhookReceipt> =
        rows.findByReceiverIdOrderByReceiptIdAsc(receiverId, Limit.of(limit)).map(::toReceipt)

    private fun toReceipt(row: JpaMerchantWebhookReceiptEntity): MerchantWebhookReceipt {
        val envelope = row.eventId?.let {
            ReceivedEnvelope(it, checkNotNull(row.eventType), checkNotNull(row.eventSequence), checkNotNull(row.environment))
        }
        return MerchantWebhookReceipt(row.receiverId, ReceiptOutcome.valueOf(row.outcome), envelope, row.keyId, row.bodySha256, row.responseStatus, row.receivedAt)
    }

    private fun isDuplicate(e: Throwable): Boolean =
        generateSequence(e) { cause -> cause.cause?.takeIf { it !== cause } }.take(MAX_CAUSE_DEPTH).filterIsInstance<SQLException>().any { it.errorCode == MYSQL_DUPLICATE_KEY }

    private companion object {
        val VERIFIED_DELIVERIES = listOf(ReceiptOutcome.ACCEPTED.name, ReceiptOutcome.DUPLICATE.name, ReceiptOutcome.SCENARIO_REFUSED.name)
        const val MAX_CAUSE_DEPTH = 20
        const val MYSQL_DUPLICATE_KEY = 1062
    }
}
