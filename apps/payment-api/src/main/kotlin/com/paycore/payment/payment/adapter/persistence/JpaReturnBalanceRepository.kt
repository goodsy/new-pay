package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.ReturnBalanceRepository
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentTransactionId
import com.paycore.payment.payment.domain.ReturnBalance
import com.paycore.payment.payment.domain.ReturnExcess
import com.paycore.payment.payment.domain.ReturnReservation
import com.paycore.payment.payment.domain.ReturnReservationState
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [ReturnBalanceRepository] JPA 구현(CANCEL-001 명세 §8.4, MANDATORY TX). 잔액 행은 `PESSIMISTIC_WRITE`로 잠가 읽고(앞의 control·payment 잠금 다음 — 같은 Payment 합계의 두 번째 방어),
 * 합계 열(completed·reserved·forced)·revision은 예약 목록에서 계산해 쓴다(CN-17 — DB CHECK의 대상). 복원 때 열과 예약 행 합계가 다르면 조용히 고치지 않고 거부한다.
 * 예약 행은 지우지 않고 순서([JpaReturnReservationEntity.seq] = domain 예약 순서)·금액은 바꾸지 않는다. 복원은 `ReturnBalance.reconstitute`(ArchUnit — persistence만)를 쓴다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaReturnBalanceRepository(
    private val reservations: ReturnReservationJpaRepository,
    private val entityManager: EntityManager,
) : ReturnBalanceRepository {

    override fun insertNew(balance: ReturnBalance, at: Instant): Long = translate(ENTITY) {
        val row = JpaReturnBalanceEntity.newRow(
            balance.paymentId.value, balance.authorizationId.value, balance.originalTransactionId.value, balance.authorized.amountMinor, balance.authorized.currency.value, at,
        )
        copySums(balance, row)
        entityManager.persist(row)
        entityManager.flush()
        balance.reservations.forEachIndexed { i, r -> entityManager.persist(newReservationRow(balance.paymentId, r, i, at)) }
        entityManager.flush()
        checkNotNull(row.version)
    }

    override fun lockByPaymentId(paymentId: PaymentId): Versioned<ReturnBalance>? = translate(ENTITY) {
        entityManager.lockRow(JpaReturnBalanceEntity::class.java, paymentId.value)?.let { Versioned(toDomain(it), checkNotNull(it.version)) }
    }

    override fun update(balance: ReturnBalance, expectedVersion: Long, at: Instant): Long = translate(ENTITY) {
        val row = entityManager.find(JpaReturnBalanceEntity::class.java, balance.paymentId.value) ?: error("return balance ${balance.paymentId.value} is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(
            row.authorizationId == balance.authorizationId.value && row.originalTransactionId == balance.originalTransactionId.value &&
                row.authorizedMinor == balance.authorized.amountMinor && row.currency == balance.authorized.currency.value,
        ) { "return balance ${row.paymentId} does not match its stored identity" }
        check(balance.revision >= row.revision) { "the business revision of ${row.paymentId} never decreases" }
        val stored = reservations.findByPayment(row.paymentId).associateBy { it.cancellationId }
        check(stored.keys.all { id -> balance.reservationOf(CancellationId(id)) != null }) { "return reservations are never removed" }
        balance.reservations.forEachIndexed { i, r ->
            val s = stored[r.cancellationId.value]
            if (s == null) {
                entityManager.persist(newReservationRow(balance.paymentId, r, i, at))
            } else {
                check(s.seq == i && s.amountMinor == r.amountMinor) { "reservation ${s.cancellationId} keeps its order and amount" }
                if (s.state != r.state.name || s.excess != r.excess?.name || s.completionSequence != r.sequence) {
                    s.state = r.state.name
                    s.excess = r.excess?.name
                    s.completionSequence = r.sequence
                    s.updatedAt = at
                }
            }
        }
        copySums(balance, row)
        row.updatedAt = at
        entityManager.flush()
        checkNotNull(row.version)
    }

    private fun copySums(balance: ReturnBalance, row: JpaReturnBalanceEntity) {
        row.completedMinor = balance.completedMinor
        row.reservedMinor = balance.reservedMinor
        row.forcedMinor = balance.forcedMinor
        row.revision = balance.revision
    }

    private fun newReservationRow(paymentId: PaymentId, r: ReturnReservation, seq: Int, at: Instant) = JpaReturnReservationEntity.newRow(
        cancellationId = r.cancellationId.value, paymentId = paymentId.value, seq = seq, amountMinor = r.amountMinor, state = r.state.name,
        excess = r.excess?.name, completionSequence = r.sequence, at = at,
    )

    private fun toDomain(row: JpaReturnBalanceEntity): ReturnBalance {
        val balance = ReturnBalance.reconstitute(
            paymentId = PaymentId(row.paymentId),
            authorizationId = AuthorizationId(row.authorizationId),
            originalTransactionId = PaymentTransactionId(row.originalTransactionId),
            authorized = Money(row.authorizedMinor, CurrencyCode(row.currency)),
            reservations = reservations.findByPayment(row.paymentId).map {
                ReturnReservation(
                    CancellationId(it.cancellationId), it.amountMinor, ReturnReservationState.valueOf(it.state), it.excess?.let(ReturnExcess::valueOf), it.completionSequence,
                )
            },
            revision = row.revision,
        )
        check(
            balance.completedMinor == row.completedMinor && balance.reservedMinor == row.reservedMinor && balance.forcedMinor == row.forcedMinor,
        ) { "return balance ${row.paymentId}: stored sums differ from its reservations" }
        return balance
    }

    private companion object {
        const val ENTITY = "return_balance"
    }
}
