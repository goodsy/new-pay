package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.ClaimedDelivery
import com.paycore.payment.payment.application.required.DeliveryAttemptResult
import com.paycore.payment.payment.application.required.DeliveryRecordOutcome
import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryRepository
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantWebhookDelivery
import com.paycore.payment.payment.domain.MerchantWebhookDeliveryId
import com.paycore.payment.payment.domain.MerchantWebhookDeliveryState
import com.paycore.payment.payment.domain.MerchantWebhookDeliveryStatus
import com.paycore.payment.payment.domain.MerchantWebhookEventId
import com.paycore.payment.payment.domain.WebhookRetrySchedule
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [MerchantWebhookDeliveryRepository] JPA 구현(WEBHOOK-001 V21, MANDATORY TX). 상태 규칙은 domain `MerchantWebhookDelivery`가 정하고 이 adapter는 잠금·복원·열 반영·claim bookkeeping만 한다.
 * claim: native `SELECT … FOR UPDATE SKIP LOCKED`로 id를 잡은 뒤 같은 순서로 Entity를 잠가 읽는다(잠근 뒤 읽으므로 영속성 컨텍스트가 최신 — `lockRows` 관례). 결과 반영은 행을 다시 잠가
 * reconstitute → domain 전이 → 열 반영. `@Version`은 잠금 밖 낡은 객체 저장을 막는 두 번째 방어다(실제 직렬화는 비관적 X 잠금).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantWebhookDeliveryRepository(private val entityManager: EntityManager) : MerchantWebhookDeliveryRepository {

    override fun insertNew(delivery: MerchantWebhookDelivery): Unit = translate(ENTITY) {
        val s = delivery.state()
        check(s.status == MerchantWebhookDeliveryStatus.PENDING && s.attemptCount == 0) { "a new delivery starts pending with no attempt" }
        val row = JpaMerchantWebhookDeliveryEntity.newRow(s.id.value, s.webhookEventId.value, s.environment.name, s.merchantAccountId.value, checkNotNull(s.nextAttemptAt))
        apply(row, s)
        entityManager.persist(row)
        entityManager.flush()
    }

    override fun claimDue(now: Instant, limit: Int, owner: String, claimUntil: Instant): List<ClaimedDelivery> = translate(ENTITY) {
        require(claimUntil.isAfter(now)) { "a claim lease ends after now" }
        @Suppress("UNCHECKED_CAST")
        val ids = entityManager.createNativeQuery(CLAIM_SQL).setParameter("now", now).setParameter("limit", limit).resultList as List<String>
        val claimed = entityManager.lockRows(JpaMerchantWebhookDeliveryEntity::class.java, ids).map { row ->
            val d = toDomain(row)
            val attemptNo = d.startAttempt(now)
            apply(row, d.state())
            row.claimOwner = owner
            row.claimUntil = claimUntil
            row.updatedAt = now
            ClaimedDelivery(d.state(), attemptNo, claimUntil)
        }
        entityManager.flush()
        claimed
    }

    override fun releaseClaim(id: MerchantWebhookDeliveryId, owner: String, attemptNo: Int): Boolean = translate(ENTITY) {
        val row = entityManager.lockRow(JpaMerchantWebhookDeliveryEntity::class.java, id.value) ?: error("a claimed delivery must exist (no deletes)")
        if (row.claimOwner != owner || row.status != MerchantWebhookDeliveryStatus.PENDING.name || row.attemptCount != attemptNo) return@translate false
        val d = toDomain(row)
        d.releaseReservedAttempt(attemptNo)
        apply(row, d.state())
        row.claimOwner = null
        row.claimUntil = null
        entityManager.flush()
        true
    }

    override fun recordResult(
        id: MerchantWebhookDeliveryId, owner: String, attemptNo: Int, result: DeliveryAttemptResult, at: Instant, schedule: WebhookRetrySchedule, jitterFraction: Double,
    ): DeliveryRecordOutcome = translate(ENTITY) {
        val row = entityManager.lockRow(JpaMerchantWebhookDeliveryEntity::class.java, id.value) ?: error("a claimed delivery must exist (no deletes)")
        check(attemptNo in 1..row.attemptCount) { "the attempt number was reserved by a claim" }
        val d = toDomain(row)
        // n-1(검토 #2): 같은 소유자라도 더 앞선 시도의 늦은 결과는 지금 진행 중인 최신 예약의 claim을 건드리지 않는다.
        val latestReservation = attemptNo == row.attemptCount
        val outcome = when (result) {
            is DeliveryAttemptResult.Success ->
                if (d.status == MerchantWebhookDeliveryStatus.DELIVERED) DeliveryRecordOutcome.ALREADY_DELIVERED else DeliveryRecordOutcome.DELIVERED.also { d.recordSuccess(at) }
            is DeliveryAttemptResult.Failure ->
                if (row.claimOwner != owner || d.status != MerchantWebhookDeliveryStatus.PENDING || !latestReservation) {
                    DeliveryRecordOutcome.CLAIM_LOST
                } else {
                    d.recordFailure(at, result.code, schedule, jitterFraction)
                    if (d.status == MerchantWebhookDeliveryStatus.GAVE_UP) DeliveryRecordOutcome.GAVE_UP else DeliveryRecordOutcome.RETRY_SCHEDULED
                }
        }
        apply(row, d.state())
        if ((row.claimOwner == owner && latestReservation) || d.status != MerchantWebhookDeliveryStatus.PENDING) {
            row.claimOwner = null
            row.claimUntil = null
        }
        row.updatedAt = at
        entityManager.flush()
        outcome
    }

    /** 시험·조회용 비잠금 읽기(영속 행 → domain, 존재할 수 없는 조합은 reconstitute가 거부). */
    internal fun find(id: MerchantWebhookDeliveryId): MerchantWebhookDelivery? = translate(ENTITY) {
        entityManager.find(JpaMerchantWebhookDeliveryEntity::class.java, id.value)?.let(::toDomain)
    }

    private fun apply(row: JpaMerchantWebhookDeliveryEntity, s: MerchantWebhookDeliveryState) {
        row.status = s.status.name
        row.attemptCount = s.attemptCount
        row.nextAttemptAt = s.nextAttemptAt
        row.firstAttemptAt = s.firstAttemptAt
        row.lastFailureCode = s.lastFailureCode
        row.deliveredAt = s.deliveredAt
        row.gaveUpAt = s.gaveUpAt
    }

    private fun toDomain(row: JpaMerchantWebhookDeliveryEntity): MerchantWebhookDelivery = MerchantWebhookDelivery.reconstitute(
        MerchantWebhookDeliveryState(
            MerchantWebhookDeliveryId(row.deliveryId), MerchantWebhookEventId(row.webhookEventId), Environment.valueOf(row.environment), MerchantAccountId(row.merchantAccountId),
            MerchantWebhookDeliveryStatus.valueOf(row.status), row.attemptCount, row.nextAttemptAt, row.firstAttemptAt, row.lastFailureCode, row.deliveredAt, row.gaveUpAt,
        ),
    )

    private companion object {
        const val ENTITY = "merchant_webhook_delivery"

        /** 시각 비교는 바인딩 Instant(`hibernate.jdbc.time_zone = UTC`) — 주입 시계 기준(n-3). 인덱스 `ix_merchant_webhook_delivery_due`. */
        const val CLAIM_SQL = "SELECT delivery_id FROM merchant_webhook_delivery WHERE status = 'PENDING' AND next_attempt_at <= :now " +
            "AND (claim_owner IS NULL OR claim_until < :now) ORDER BY next_attempt_at, delivery_id LIMIT :limit FOR UPDATE SKIP LOCKED"
    }
}
