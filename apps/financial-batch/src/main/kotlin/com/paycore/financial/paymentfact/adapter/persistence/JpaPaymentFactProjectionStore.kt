package com.paycore.financial.paymentfact.adapter.persistence

import com.paycore.financial.paymentfact.adapter.persistence.PaymentFactPersistenceFailures.translate
import com.paycore.financial.paymentfact.application.required.PaymentFactProjectionStore
import com.paycore.financial.paymentfact.domain.AuthorizedPaymentFact
import com.paycore.financial.paymentfact.domain.CancelledPaymentFact
import com.paycore.financial.paymentfact.application.required.OpenHold
import com.paycore.financial.paymentfact.domain.HoldReason
import com.paycore.financial.paymentfact.domain.HoldReleaseDecision
import com.paycore.financial.paymentfact.domain.HoldReleaseTrigger
import com.paycore.financial.paymentfact.domain.PaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFactHistory
import com.paycore.financial.paymentfact.domain.PaymentFactIdentity
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [PaymentFactProjectionStore] 구현(JPA, MANDATORY TX, 쓰기 끝 flush).
 *
 * JPA 선택(TECHNOLOGY §3 — 이번 코드에 연결):
 * - **잠금**: 직렬화 행은 native IODKU로 자리를 만든 뒤 `EntityManager.find` + `refresh(PESSIMISTIC_WRITE)`(= `SELECT … FOR UPDATE`)로 잠근다. refresh라서 영속성 컨텍스트에
 *   먼저 들어와 있던 Entity도 잠금 시점의 최신 커밋 값으로 다시 읽는다(IODKU는 영속성 컨텍스트를 거치지 않는다).
 *   예: 같은 Payment의 승인과 취소가 동시에 오면 두 TX가 이 행에서 줄을 서고, 뒤 TX는 앞 TX가 커밋한 승인을 이력으로 읽어 취소를 보류 없이 반영한다.
 * - **append-only**: 사실·보류 Entity는 `@Immutable`이고 여기서는 persist만 한다(변경 감지로 UPDATE가 나갈 경로가 없다). cascade·orphanRemoval 연관이 없어 사실을 지우는
 *   경로도 없다.
 * - **flush ≠ commit**: persist 뒤 flush로 INSERT를 보내 UNIQUE·CHECK 위반을 이 호출 안에서 분류한다. 커밋은 Use Case TX 끝에서 Inbox 기록과 함께 한 번 일어난다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPaymentFactProjectionStore(
    private val streams: PaymentFactStreamJpaRepository,
    private val authorizations: AuthorizedPaymentFactJpaRepository,
    private val cancellations: CancelledPaymentFactJpaRepository,
    private val entityManager: EntityManager,
) : PaymentFactProjectionStore {

    override fun lockStream(identity: PaymentFactIdentity, at: Instant): PaymentFactIdentity? = translate(STREAM) {
        streams.insertIfAbsent(identity.paymentId, identity.environment.name, identity.merchantAccountId, identity.merchantSiteId, identity.merchantOrderId, at)
        val row = checkNotNull(entityManager.find(JpaPaymentFactStreamEntity::class.java, identity.paymentId)) { "stream row must exist after insert-if-absent" }
        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE)
        // 반영 건수 0 = 이 TX가 방금 만든 자리(첫 사실). 충돌·중복 판정 TX는 rollback되므로 0인 행이 커밋되어 남지 않는다.
        if (row.factCount == 0) null else PaymentFactPersistenceMapper.toOwner(row)
    }

    override fun history(paymentId: String, owner: PaymentFactIdentity?): PaymentFactHistory = translate(STREAM) {
        PaymentFactHistory(
            owner = owner,
            authorizations = authorizations.findByPayment(paymentId).map(PaymentFactPersistenceMapper::toDomain),
            cancellations = cancellations.findByPayment(paymentId).map(PaymentFactPersistenceMapper::toDomain),
        )
    }

    override fun insertAuthorization(fact: AuthorizedPaymentFact, sourceEventId: String, receivedAt: Instant): Unit = translate(AUTHORIZATION) {
        entityManager.persist(PaymentFactPersistenceMapper.toRow(fact, sourceEventId, receivedAt))
        entityManager.flush()
    }

    override fun insertCancellation(fact: CancelledPaymentFact, originalKnownAtReceipt: Boolean, sourceEventId: String, receivedAt: Instant): Unit =
        translate(CANCELLATION) {
            entityManager.persist(PaymentFactPersistenceMapper.toRow(fact, originalKnownAtReceipt, sourceEventId, receivedAt))
            entityManager.flush()
        }

    override fun insertHolds(fact: PaymentFact, reasons: Set<HoldReason>, sourceEventId: String, openedAt: Instant): Unit = translate(HOLD) {
        reasons.forEach { reason ->
            entityManager.persist(JpaPaymentFactHoldEntity.newRow(fact.transactionId, fact.kind.name, fact.identity.paymentId, reason.name, sourceEventId, openedAt))
        }
        entityManager.flush()
    }

    override fun recordFact(paymentId: String, at: Instant): Unit = translate(STREAM) {
        val row = checkNotNull(entityManager.find(JpaPaymentFactStreamEntity::class.java, paymentId)) { "stream row must be locked first" }
        row.factCount += 1
        row.updatedAt = at
        entityManager.flush()
    }

    override fun unreleasedOriginalUnknownHolds(paymentId: String, originalTransactionId: String): List<OpenHold> = translate(RELEASE) {
        rows(
            entityManager.createNativeQuery(
                "SELECT h.id, h.transaction_id FROM payment_fact_hold h JOIN payment_fact_cancellation c ON c.transaction_id = h.transaction_id " +
                    "WHERE h.payment_id = ?1 AND h.reason = 'ORIGINAL_UNKNOWN' AND c.original_transaction_id = ?2 " +
                    "AND NOT EXISTS (SELECT 1 FROM payment_fact_hold_release r WHERE r.hold_id = h.id) ORDER BY h.id",
            ).setParameter(1, paymentId).setParameter(2, originalTransactionId),
        ).map { OpenHold((it[0] as Number).toLong(), it[1] as String) }
    }

    override fun hasHolds(transactionId: String): Boolean = translate(HOLD) {
        (entityManager.createNativeQuery("SELECT COUNT(*) FROM payment_fact_hold WHERE transaction_id = ?1").setParameter(1, transactionId)
            .singleResult as Number).toLong() > 0
    }

    override fun insertHoldReleases(
        paymentId: String,
        authorizationTransactionId: String,
        decisions: List<Pair<OpenHold, HoldReleaseDecision>>,
        trigger: HoldReleaseTrigger,
        at: Instant,
    ): Unit = translate(RELEASE) {
        decisions.forEach { (hold, decision) ->
            require(hold.cancellationTransactionId == decision.cancellationTransactionId) { "decision must belong to the hold" }
            val reasons = decision.mismatches.sortedBy { it.ordinal }.joinToString(",") { it.name }.ifEmpty { null }
            entityManager.persist(
                JpaPaymentFactHoldReleaseEntity.newRow(
                    hold.holdId, paymentId, hold.cancellationTransactionId, authorizationTransactionId, decision.outcome.name, reasons, trigger.name, at,
                ),
            )
        }
        entityManager.flush()
    }

    override fun paymentsWithReleasableHolds(limit: Int): List<String> = translate(RELEASE) {
        require(limit in 1..MAX_SWEEP) { "sweep limit must be within 1..$MAX_SWEEP" }
        // 리뷰 m-1: 원승인이 **같은 Payment**의 것일 때만 대상이다. 다른 Payment의 승인을 가리키는 취소 보류(정합성 보류 — 운영자 검토)는 SWEEP이 해제할 수 없으므로
        // 이 조건이 없으면 매 회차 다시 뽑혀 LIMIT를 차지하고 다른 보류가 처리되지 못한다.
        val query = entityManager.createNativeQuery(
            "SELECT DISTINCT h.payment_id FROM payment_fact_hold h JOIN payment_fact_cancellation c ON c.transaction_id = h.transaction_id " +
                "JOIN payment_fact_authorization a ON a.transaction_id = c.original_transaction_id " +
                "WHERE h.reason = 'ORIGINAL_UNKNOWN' AND a.payment_id = h.payment_id " +
                "AND NOT EXISTS (SELECT 1 FROM payment_fact_hold_release r WHERE r.hold_id = h.id) " +
                "ORDER BY h.payment_id LIMIT ?1",
        ).setParameter(1, limit)
        query.resultList.map { it as String }
    }

    override fun lockExistingStream(paymentId: String): PaymentFactIdentity? = translate(STREAM) {
        val row = entityManager.find(JpaPaymentFactStreamEntity::class.java, paymentId) ?: return@translate null
        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE)
        if (row.factCount == 0) null else PaymentFactPersistenceMapper.toOwner(row)
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(query: jakarta.persistence.Query): List<Array<Any?>> = query.resultList as List<Array<Any?>>

    private companion object {
        const val RELEASE = "payment_fact_hold_release"
        const val MAX_SWEEP = 1000
        const val STREAM = "payment_fact_stream"
        const val AUTHORIZATION = "payment_fact_authorization"
        const val CANCELLATION = "payment_fact_cancellation"
        const val HOLD = "payment_fact_hold"
    }
}
