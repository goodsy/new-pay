package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.PaymentId
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [PaymentAuthorizationRepository] JPA 구현(AUTH-001 명세 §7). 공통 관례는 [JpaPaymentRepository] 설명과 같다(MANDATORY TX, 쓰기 끝 flush로 제약 위반·version 충돌을 Port 안에서
 * [PersistenceFailure]로, 새 행은 persist만, 갱신은 잠가 읽은 Entity에 옮겨 변경 감지).
 *
 * - Attempt: 기관 요청 namespace·준비 시각은 바꿀 수 없고 상태·원인만 옮긴다(각 행 `@Version`). 새 Attempt는 INSERT.
 * - 관찰·거래: append-only. 저장된 행은 domain에 같은 내용으로 남아 있어야 하며(아니면 결함), 없던 행만 INSERT한다(`@Immutable` + runtime 권한 SELECT·INSERT).
 * - 루트 상태가 그대로이고 자식만 늘거나 바뀌어도 루트 version을 올린다(`PESSIMISTIC_FORCE_INCREMENT`) — 같은 승인을 동시에 바꾸는 다른 TX가 version으로 충돌한다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPaymentAuthorizationRepository(
    private val authorizations: PaymentAuthorizationJpaRepository,
    private val attempts: AuthorizationAttemptJpaRepository,
    private val observations: InstitutionObservationJpaRepository,
    private val transactions: PaymentTransactionJpaRepository,
    private val entityManager: EntityManager,
) : PaymentAuthorizationRepository {

    override fun insertNew(authorization: PaymentAuthorization): Long = translate(ENTITY) {
        val row = PaymentAuthorizationPersistenceMapper.newRow(authorization)
        entityManager.persist(row)
        entityManager.flush()
        authorization.attempts.forEachIndexed { i, a -> entityManager.persist(PaymentAuthorizationPersistenceMapper.newAttemptRow(authorization.id, a, i)) }
        PaymentAuthorizationPersistenceMapper.observationRows(authorization).forEach { (o, variant) ->
            entityManager.persist(PaymentAuthorizationPersistenceMapper.newObservationRow(authorization.id, o, variant))
        }
        authorization.transaction?.let {
            entityManager.persist(PaymentAuthorizationPersistenceMapper.newTransactionRow(it, PaymentAuthorizationPersistenceMapper.recordedBy(authorization)))
        }
        entityManager.flush()
        checkNotNull(row.version)
    }

    override fun lock(id: AuthorizationId): Versioned<PaymentAuthorization>? = translate(ENTITY) {
        entityManager.lockRow(JpaPaymentAuthorizationEntity::class.java, id.value)?.let { Versioned(toDomain(it), checkNotNull(it.version)) }
    }

    override fun lockByPaymentId(paymentId: PaymentId): Versioned<PaymentAuthorization>? = translate(ENTITY) {
        authorizations.findIdByPaymentId(paymentId.value)?.let { id ->
            entityManager.lockRow(JpaPaymentAuthorizationEntity::class.java, id)?.let { Versioned(toDomain(it), checkNotNull(it.version)) }
        }
    }

    override fun update(authorization: PaymentAuthorization, expectedVersion: Long): Long = translate(ENTITY) {
        val row = entityManager.find(JpaPaymentAuthorizationEntity::class.java, authorization.id.value)
            ?: error("authorization ${authorization.id.value} is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        val expected = PaymentAuthorizationPersistenceMapper.newRow(authorization)
        check(
            row.paymentId == expected.paymentId && row.amountMinor == expected.amountMinor && row.currency == expected.currency &&
                row.planKind == expected.planKind && row.authenticationSessionId == expected.authenticationSessionId &&
                row.limitPolicyVersion == expected.limitPolicyVersion && row.limitBusinessDate == expected.limitBusinessDate,
        ) { "authorization ${row.authorizationId} does not match its stored identity" }

        var childChanged = false
        val storedAttempts = attempts.findByAuthorization(row.authorizationId).associateBy { it.attemptId }
        authorization.attempts.forEachIndexed { i, a ->
            val stored = storedAttempts[a.id.value]
            if (stored == null) {
                entityManager.persist(PaymentAuthorizationPersistenceMapper.newAttemptRow(authorization.id, a, i))
                childChanged = true
            } else {
                check(PaymentAuthorizationPersistenceMapper.toAttempt(stored).providerRequest == a.providerRequest && stored.seq == i) {
                    "attempt ${a.id.value} does not match its stored identity"
                }
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
        }
        check(storedAttempts.keys.all { id -> authorization.attempts.any { it.id.value == id } }) { "attempts are never removed" }

        val storedObservations = observations.findByAuthorization(row.authorizationId)
            .associate { (it.attemptId to it.source to it.sourceResultId to it.variant) to PaymentAuthorizationPersistenceMapper.toObservation(it) }
        val expectedObservations = PaymentAuthorizationPersistenceMapper.observationRows(authorization)
        val expectedKeys = expectedObservations.associate { (o, v) -> (o.attemptId.value to o.source.name to o.sourceResultId to v) to o }
        check(storedObservations.all { (k, o) -> expectedKeys[k] == o }) { "institution observations of ${row.authorizationId} are append-only" }
        expectedObservations.filter { (o, v) -> (o.attemptId.value to o.source.name to o.sourceResultId to v) !in storedObservations }.forEach { (o, v) ->
            entityManager.persist(PaymentAuthorizationPersistenceMapper.newObservationRow(authorization.id, o, v))
            childChanged = true
        }

        val storedTransaction = transactions.findAuthorizationTransaction(row.authorizationId)?.let(PaymentAuthorizationPersistenceMapper::toTransaction)
        val transaction = authorization.transaction
        when {
            storedTransaction != null -> check(storedTransaction == transaction) { "the authorization transaction of ${row.authorizationId} is immutable" }
            transaction != null -> {
                entityManager.persist(PaymentAuthorizationPersistenceMapper.newTransactionRow(transaction, PaymentAuthorizationPersistenceMapper.recordedBy(authorization)))
                childChanged = true
            }
        }

        val rootChanged = row.status != authorization.status.name || row.failureEvidence != authorization.failureEvidence?.name
        row.status = authorization.status.name
        row.failureEvidence = authorization.failureEvidence?.name
        if (!rootChanged && childChanged) entityManager.lock(row, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        entityManager.flush()
        checkNotNull(row.version)
    }

    private fun toDomain(row: JpaPaymentAuthorizationEntity): PaymentAuthorization =
        PaymentAuthorizationPersistenceMapper.toAuthorization(
            row,
            attempts.findByAuthorization(row.authorizationId),
            observations.findByAuthorization(row.authorizationId),
            transactions.findAuthorizationTransaction(row.authorizationId),
        )

    private companion object {
        const val ENTITY = "payment_authorization"
    }
}
