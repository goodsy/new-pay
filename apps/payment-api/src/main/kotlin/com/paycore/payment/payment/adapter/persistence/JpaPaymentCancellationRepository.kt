package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.PaymentCancellationRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.PaymentCancellation
import com.paycore.payment.payment.domain.PaymentId
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [PaymentCancellationRepository] JPA 구현(CANCEL-001 명세 §8.4 — [JpaPaymentAuthorizationRepository]와 같은 관례: MANDATORY TX, 쓰기 끝 flush로 제약 위반·version 충돌을 Port 안에서
 * [PersistenceFailure]로, 새 행은 persist만, 갱신은 잠가 읽은 Entity에 옮겨 변경 감지).
 *
 * - Attempt: 기관 요청 namespace·준비 시각은 바꿀 수 없고 상태·원인·Route 프로파일 버전만 옮긴다(`@Version`).
 * - 관찰·CANCEL 거래: append-only. 저장된 행은 domain에 같은 내용으로 남아 있어야 하며(아니면 결함), 없던 행만 INSERT한다(`@Immutable` + runtime 권한 SELECT·INSERT).
 * - 루트 상태가 그대로이고 자식만 늘거나 바뀌어도 루트 version을 올린다(`PESSIMISTIC_FORCE_INCREMENT`).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPaymentCancellationRepository(
    private val cancellations: PaymentCancellationJpaRepository,
    private val attempts: CancelAttemptJpaRepository,
    private val observations: CancelObservationJpaRepository,
    private val transactions: PaymentTransactionJpaRepository,
    private val entityManager: EntityManager,
) : PaymentCancellationRepository {

    override fun insertNew(cancellation: PaymentCancellation): Long = translate(ENTITY) {
        // mid-review #1 n-3: 원 거래는 같은 Payment·같은 Authorization의 AUTHORIZATION 거래여야 한다(FK는 다른 Payment의 거래도 통과시킨다). CANCEL 거래의 원 거래·Payment·Authorization은
        // domain이 이 취소의 값과 같게 강제하므로(complete) 여기서 함께 보장된다.
        val original = transactions.findById(cancellation.originalTransactionId.value).orElse(null)
        check(
            original != null && original.type == "AUTHORIZATION" && original.authorizationId == cancellation.authorizationId.value &&
                original.paymentId == cancellation.paymentId.value,
        ) { "cancellation ${cancellation.id.value} must refer to its own payment's authorization transaction" }
        val row = PaymentCancellationPersistenceMapper.newRow(cancellation)
        entityManager.persist(row)
        entityManager.flush()
        cancellation.attempts.forEach { entityManager.persist(PaymentCancellationPersistenceMapper.newAttemptRow(cancellation.id, it)) }
        PaymentCancellationPersistenceMapper.observationRows(cancellation).forEach { (o, v) ->
            entityManager.persist(PaymentCancellationPersistenceMapper.newObservationRow(cancellation.id, o, v))
        }
        cancellation.transaction?.let { entityManager.persist(PaymentCancellationPersistenceMapper.newTransactionRow(it)) }
        entityManager.flush()
        checkNotNull(row.version)
    }

    override fun lock(id: CancellationId): Versioned<PaymentCancellation>? = translate(ENTITY) {
        entityManager.lockRow(JpaPaymentCancellationEntity::class.java, id.value)?.let { Versioned(toDomain(it), checkNotNull(it.version)) }
    }

    override fun find(id: CancellationId): PaymentCancellation? = translate(ENTITY) {
        entityManager.find(JpaPaymentCancellationEntity::class.java, id.value)?.let(::toDomain)
    }

    override fun findIdByMerchantKey(paymentId: PaymentId, merchantCancelId: MerchantCancelId): CancellationId? = translate(ENTITY) {
        cancellations.findIdByMerchantKey(paymentId.value, merchantCancelId.value)?.let(::CancellationId)
    }

    override fun findIdByApprovalId(approvalId: OperatorApprovalId): CancellationId? = translate(ENTITY) {
        cancellations.findIdByApprovalId(approvalId.value)?.let(::CancellationId)
    }

    override fun update(cancellation: PaymentCancellation, expectedVersion: Long): Long = translate(ENTITY) {
        val row = entityManager.find(JpaPaymentCancellationEntity::class.java, cancellation.id.value) ?: error("cancellation ${cancellation.id.value} is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        val expected = PaymentCancellationPersistenceMapper.newRow(cancellation)
        check(
            row.paymentId == expected.paymentId && row.authorizationId == expected.authorizationId && row.amountMinor == expected.amountMinor &&
                row.currency == expected.currency && row.scope == expected.scope && row.initiatorKind == expected.initiatorKind &&
                row.merchantCancelId == expected.merchantCancelId && row.approvalId == expected.approvalId,
        ) { "cancellation ${row.cancellationId} does not match its stored identity" }

        var childChanged = false
        val storedAttempts = attempts.findByCancellation(row.cancellationId).associateBy { it.attemptId }
        check(storedAttempts.keys == cancellation.attempts.map { it.id.value }.toSet()) { "a cancellation keeps exactly its stored attempt" }
        cancellation.attempts.forEach { a ->
            val stored = checkNotNull(storedAttempts[a.id.value])
            check(PaymentCancellationPersistenceMapper.toAttempt(stored).providerRequest == a.providerRequest) { "attempt ${a.id.value} does not match its stored identity" }
            if (stored.state != a.state.name || stored.abandonCause != a.abandonCause?.name || stored.unknownCause != a.unknownCause?.name ||
                stored.routeProfileVersion != a.routeProfileVersion
            ) {
                stored.state = a.state.name
                stored.abandonCause = a.abandonCause?.name
                stored.unknownCause = a.unknownCause?.name
                stored.routeProfileVersion = a.routeProfileVersion
                childChanged = true
            }
        }

        val storedObservations = observations.findByCancellation(row.cancellationId)
            .associate { (it.attemptId to it.source to it.sourceResultId to it.variant) to PaymentCancellationPersistenceMapper.toObservation(it) }
        val expectedObservations = PaymentCancellationPersistenceMapper.observationRows(cancellation)
        val expectedKeys = expectedObservations.associate { (o, v) -> (o.attemptId.value to o.source.name to o.sourceResultId to v) to o }
        check(storedObservations.all { (k, o) -> expectedKeys[k] == o }) { "cancel observations of ${row.cancellationId} are append-only" }
        expectedObservations.filter { (o, v) -> (o.attemptId.value to o.source.name to o.sourceResultId to v) !in storedObservations }.forEach { (o, v) ->
            entityManager.persist(PaymentCancellationPersistenceMapper.newObservationRow(cancellation.id, o, v))
            childChanged = true
        }

        val storedTransaction = transactions.findCancelTransaction(row.cancellationId)?.let(PaymentCancellationPersistenceMapper::toTransaction)
        val transaction = cancellation.transaction
        when {
            storedTransaction != null -> check(storedTransaction == transaction) { "the cancel transaction of ${row.cancellationId} is immutable" }
            transaction != null -> {
                check(
                    transaction.paymentId.value == row.paymentId && transaction.authorizationId.value == row.authorizationId &&
                        transaction.originalTransactionId.value == row.originalTransactionId,
                ) { "the cancel transaction of ${row.cancellationId} must match its cancellation" }
                entityManager.persist(PaymentCancellationPersistenceMapper.newTransactionRow(transaction))
                childChanged = true
            }
        }

        check(cancellation.incidentCount >= row.incidentCount) { "the incident count of ${row.cancellationId} never decreases" }
        val rootChanged = row.status != cancellation.status.name || row.failureEvidence != cancellation.failureEvidence?.name || row.incidentCount != cancellation.incidentCount
        row.status = cancellation.status.name
        row.failureEvidence = cancellation.failureEvidence?.name
        row.incidentCount = cancellation.incidentCount
        if (!rootChanged && childChanged) entityManager.lock(row, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        entityManager.flush()
        checkNotNull(row.version)
    }

    private fun toDomain(row: JpaPaymentCancellationEntity): PaymentCancellation =
        PaymentCancellationPersistenceMapper.toCancellation(
            row,
            attempts.findByCancellation(row.cancellationId),
            observations.findByCancellation(row.cancellationId),
            transactions.findCancelTransaction(row.cancellationId),
        )

    private companion object {
        const val ENTITY = "payment_cancellation"
    }
}
