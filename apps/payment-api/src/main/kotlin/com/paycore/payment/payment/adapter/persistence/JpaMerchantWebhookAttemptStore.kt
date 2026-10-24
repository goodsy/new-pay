package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.MerchantWebhookAttempt
import com.paycore.payment.payment.application.required.MerchantWebhookAttemptStore
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [MerchantWebhookAttemptStore] JPA 구현(WEBHOOK-001 V25, MANDATORY TX — 결과 반영과 같은 TX). 같은 (전달, 시도 번호) 두 번 → 1062 → UniqueConflict(MERCHANT_WEBHOOK_ATTEMPT). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantWebhookAttemptStore(private val entityManager: EntityManager) : MerchantWebhookAttemptStore {

    override fun append(attempt: MerchantWebhookAttempt): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaMerchantWebhookAttemptEntity.newRow(
                attempt.deliveryId.value, attempt.attemptNo, attempt.worker, attempt.endpointRef, attempt.signingKeyIds, attempt.outcome, attempt.httpStatus,
                attempt.scheduleVersion, attempt.startedAt, attempt.finishedAt,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val ENTITY = "merchant_webhook_attempt"
    }
}
