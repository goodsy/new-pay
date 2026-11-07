package com.paycore.financial.paymentfact.adapter.persistence

import com.paycore.financial.paymentfact.adapter.persistence.PaymentFactPersistenceFailures.translate
import com.paycore.financial.paymentfact.application.provided.AcquisitionAuthorizationKey
import com.paycore.financial.paymentfact.application.provided.AcquisitionCancellationKey
import com.paycore.financial.paymentfact.application.provided.AcquisitionSourceAuthorization
import com.paycore.financial.paymentfact.application.provided.AcquisitionSourceCancellation
import com.paycore.financial.paymentfact.application.provided.AcquisitionSourcePayment
import com.paycore.financial.paymentfact.application.required.PaymentFactAcquisitionReadStore
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [PaymentFactAcquisitionReadStore] 구현(JPA, MANDATORY TX).
 *
 * JPA 선택(이번 코드에 연결): 키 조회는 native 스칼라(Entity를 영속성 컨텍스트에 올리지 않는다 — 잠금 전 읽은 값이 잠금 뒤 판정에 섞이지 않게). 잠금은
 * `SELECT … FOR UPDATE`(payment_id 순 한 문장 — 취소 Inbox TX의 같은 행 잠금과 줄을 선다). 잠금 뒤 승인·취소는 JPQL로 처음 읽으므로 READ COMMITTED의 최신
 * 커밋 값이다(이 TX 안에서 같은 Entity를 먼저 읽지 않았다 — 1차 캐시의 stale 값 없음). 사실 Entity는 `@Immutable`이라 읽기가 UPDATE를 만들지 않는다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPaymentFactAcquisitionReadStore(private val entityManager: EntityManager) : PaymentFactAcquisitionReadStore {

    override fun authorizationKeys(acquirerCode: String, environment: String, approvedUpTo: Instant): List<AcquisitionAuthorizationKey> =
        translate(AUTHORIZATION) {
            val rows = entityManager.createNativeQuery(
                "SELECT payment_id, transaction_id FROM payment_fact_authorization WHERE acquirer_code = :acquirer AND environment = :environment " +
                    "AND approved_at <= :upTo ORDER BY approved_at, transaction_id",
            ).setParameter("acquirer", acquirerCode).setParameter("environment", environment).setParameter("upTo", approvedUpTo).resultList
            rows.map { row ->
                val r = row as Array<*>
                AcquisitionAuthorizationKey(r[0] as String, r[1] as String)
            }
        }

    override fun cancellationKeys(acquirerCode: String, environment: String, cancelledUpTo: Instant): List<AcquisitionCancellationKey> =
        translate(AUTHORIZATION) {
            // 원승인 투영과 같은 Payment·같은 원승인 거래로 join(같은 모듈 표 — paymentfact 소유). native 스칼라(Entity를 올리지 않는다 — 잠금 뒤 판정과 섞이지 않게).
            val rows = entityManager.createNativeQuery(
                "SELECT c.payment_id, c.transaction_id, c.original_transaction_id FROM payment_fact_cancellation c " +
                    "JOIN payment_fact_authorization a ON a.transaction_id = c.original_transaction_id AND a.payment_id = c.payment_id " +
                    "WHERE a.acquirer_code = :acquirer AND a.environment = :environment AND c.cancelled_at <= :upTo ORDER BY c.cancelled_at, c.transaction_id",
            ).setParameter("acquirer", acquirerCode).setParameter("environment", environment).setParameter("upTo", cancelledUpTo).resultList
            rows.map { row ->
                val r = row as Array<*>
                AcquisitionCancellationKey(r[0] as String, r[1] as String, r[2] as String)
            }
        }

    override fun lockAndRead(paymentIds: Collection<String>): List<AcquisitionSourcePayment> = translate(STREAM) {
        val ids = paymentIds.toSortedSet().toList()
        // 고정 순서(payment_id 오름차순)로 잠근다 — 여러 행을 잠그는 TX끼리 교착하지 않는다(Inbox TX는 한 행만 잠근다).
        ids.chunked(CHUNK).forEach { chunk ->
            entityManager.createNativeQuery("SELECT payment_id FROM payment_fact_stream WHERE payment_id IN (:ids) ORDER BY payment_id FOR UPDATE")
                .setParameter("ids", chunk).resultList
        }
        readPayments(ids)
    }

    override fun read(paymentIds: Collection<String>): List<AcquisitionSourcePayment> = translate(STREAM) { readPayments(paymentIds.toSortedSet().toList()) }

    override fun paymentIdsOf(authorizationTransactionIds: Collection<String>): Map<String, String> = translate(AUTHORIZATION) {
        authorizationTransactionIds.distinct().chunked(CHUNK).flatMap { chunk ->
            entityManager.createNativeQuery("SELECT transaction_id, payment_id FROM payment_fact_authorization WHERE transaction_id IN (:ids)")
                .setParameter("ids", chunk).resultList.map { row ->
                    val r = row as Array<*>
                    (r[0] as String) to (r[1] as String)
                }
        }.toMap()
    }

    private fun readPayments(ids: List<String>): List<AcquisitionSourcePayment> {
        val authorizations = ids.chunked(CHUNK).flatMap { chunk ->
            entityManager.createQuery(
                "select a from JpaAuthorizedPaymentFactEntity a where a.paymentId in :ids order by a.paymentId, a.transactionId",
                JpaAuthorizedPaymentFactEntity::class.java,
            ).setParameter("ids", chunk).resultList
        }.groupBy { it.paymentId }
        val cancellations = ids.chunked(CHUNK).flatMap { chunk ->
            entityManager.createQuery(
                "select c from JpaCancelledPaymentFactEntity c where c.paymentId in :ids order by c.paymentId, c.transactionId",
                JpaCancelledPaymentFactEntity::class.java,
            ).setParameter("ids", chunk).resultList
        }.groupBy { it.paymentId }
        val held = ids.chunked(CHUNK).flatMap { chunk ->
            entityManager.createNativeQuery(
                "SELECT h.payment_id, h.transaction_id FROM payment_fact_hold h WHERE h.payment_id IN (:ids) " +
                    "AND NOT EXISTS (SELECT 1 FROM payment_fact_hold_release r WHERE r.hold_id = h.id AND r.decision = 'RELEASED')",
            ).setParameter("ids", chunk).resultList.map { row ->
                val r = row as Array<*>
                (r[0] as String) to (r[1] as String)
            }
        }.groupBy({ it.first }, { it.second })
        return ids.map { id ->
            AcquisitionSourcePayment(
                paymentId = id,
                authorizations = authorizations[id].orEmpty().map(::toSource),
                cancellations = cancellations[id].orEmpty().map(::toSource),
                unreleasedHoldTransactionIds = held[id].orEmpty().toSet(),
            )
        }
    }

    private fun toSource(a: JpaAuthorizedPaymentFactEntity) = AcquisitionSourceAuthorization(
        transactionId = a.transactionId, paymentId = a.paymentId, environment = a.environment, merchantAccountId = a.merchantAccountId,
        amountMinor = a.amountMinor, currency = a.currency, planKind = a.planKind, installmentMonths = a.installmentMonths, cardType = a.cardType,
        issuerCode = a.issuerCode, acquirerCode = a.acquirerCode, approvalNumber = a.approvalNumber, providerTransactionId = a.providerTransactionId,
        institutionTransactionDate = a.institutionTransactionDate, approvedAt = a.approvedAt,
    )

    private fun toSource(c: JpaCancelledPaymentFactEntity) = AcquisitionSourceCancellation(
        transactionId = c.transactionId, originalTransactionId = c.originalTransactionId, amountMinor = c.amountMinor, currency = c.currency,
        providerTransactionId = c.providerTransactionId, originalProviderTransactionId = c.originalProviderTransactionId, cancelledAt = c.cancelledAt,
        merchantAccountId = c.merchantAccountId, institutionTransactionDate = c.institutionTransactionDate,
    )

    private companion object {
        const val CHUNK = 500
        const val STREAM = "payment_fact_stream"
        const val AUTHORIZATION = "payment_fact_authorization"
    }
}
