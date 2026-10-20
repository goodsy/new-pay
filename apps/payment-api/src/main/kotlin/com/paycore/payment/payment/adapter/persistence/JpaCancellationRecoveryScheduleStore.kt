package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.CancellationRecoverySchedule
import com.paycore.payment.payment.application.required.CancellationRecoveryScheduleStore
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** [CancellationRecoveryScheduleStore] JPA 구현(MANDATORY TX — CN-24, `JpaAuthorizationRecoveryScheduleStore`와 같은 규칙). 식별·주문 키·TX1 설정 버전은 바꾸지 않는다(결함이면 거부). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaCancellationRecoveryScheduleStore(
    private val rows: CancellationRecoveryScheduleJpaRepository,
    private val entityManager: EntityManager,
) : CancellationRecoveryScheduleStore {

    override fun insert(schedule: CancellationRecoverySchedule, at: Instant): Unit = translate(ENTITY) {
        check(schedule.inquiryCount == 0 && schedule.escalatedAt == null && schedule.closedAt == null) { "a new schedule starts open with no inquiry" }
        val o = schedule.orderKey
        entityManager.persist(
            JpaCancellationRecoveryScheduleEntity.newRow(
                schedule.attemptId.value, schedule.cancellationId.value, schedule.paymentId.value, o.environment.name, o.merchantAccountId.value, o.merchantOrderId.value,
                schedule.dispatchLeaseUntil, schedule.nextCheckAt, schedule.recoveryConfigVersion, at,
            ),
        )
        entityManager.flush()
    }

    override fun lock(attemptId: CancelAttemptId): CancellationRecoverySchedule? = translate(ENTITY) {
        entityManager.lockRow(JpaCancellationRecoveryScheduleEntity::class.java, attemptId.value)?.let(::toSchedule)
    }

    override fun update(schedule: CancellationRecoverySchedule, at: Instant): Unit = translate(ENTITY) {
        val row = entityManager.lockRow(JpaCancellationRecoveryScheduleEntity::class.java, schedule.attemptId.value) ?: error("cancellation recovery schedule is not stored")
        check(row.cancellationId == schedule.cancellationId.value && row.paymentId == schedule.paymentId.value) { "schedule identity never changes" }
        check(schedule.inquiryCount >= row.inquiryCount) { "inquiry count never decreases" }
        check(row.closedAt == null || schedule.closedAt == row.closedAt) { "a closed schedule stays closed" }
        check(schedule.recoveryConfigVersion == row.recoveryConfigVersion) { "the recovery config version recorded at TX1 never changes" }
        check(row.escalationConfigVersion == null || schedule.escalationConfigVersion == row.escalationConfigVersion) { "the escalation config version is written once" }
        row.dispatchLeaseUntil = schedule.dispatchLeaseUntil
        row.nextCheckAt = schedule.nextCheckAt
        row.inquiryCount = schedule.inquiryCount
        row.escalatedAt = schedule.escalatedAt
        row.closedAt = schedule.closedAt
        row.escalationConfigVersion = schedule.escalationConfigVersion
        row.updatedAt = at
        entityManager.flush()
    }

    override fun claimDue(now: Instant, limit: Int): List<CancellationRecoverySchedule> = translate(ENTITY) {
        entityManager.lockRows(JpaCancellationRecoveryScheduleEntity::class.java, rows.claimDueIds(now, limit)).map(::toSchedule)
    }

    private fun toSchedule(row: JpaCancellationRecoveryScheduleEntity) = CancellationRecoverySchedule(
        attemptId = CancelAttemptId(row.attemptId),
        cancellationId = CancellationId(row.cancellationId),
        paymentId = PaymentId(row.paymentId),
        orderKey = OrderKey(Environment.valueOf(row.environment), MerchantAccountId(row.merchantAccountId), MerchantOrderId(row.merchantOrderId)),
        dispatchLeaseUntil = row.dispatchLeaseUntil,
        nextCheckAt = row.nextCheckAt,
        inquiryCount = row.inquiryCount,
        escalatedAt = row.escalatedAt,
        closedAt = row.closedAt,
        recoveryConfigVersion = row.recoveryConfigVersion,
        escalationConfigVersion = row.escalationConfigVersion,
    )

    private companion object {
        const val ENTITY = "cancellation_recovery_schedule"
    }
}
