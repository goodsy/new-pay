package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.AuthorizationRecoveryScheduleStore
import com.paycore.payment.payment.application.required.RecoverySchedule
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AuthorizationId
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

/** [AuthorizationRecoveryScheduleStore] JPA 구현(MANDATORY TX). 식별·주문 키는 바꾸지 않는다(결함이면 거부). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAuthorizationRecoveryScheduleStore(
    private val rows: RecoveryScheduleJpaRepository,
    private val entityManager: EntityManager,
) : AuthorizationRecoveryScheduleStore {

    override fun insert(schedule: RecoverySchedule, at: Instant): Unit = translate(ENTITY) {
        val o = schedule.orderKey
        entityManager.persist(
            JpaRecoveryScheduleEntity.newRow(
                schedule.attemptId.value, schedule.authorizationId.value, schedule.paymentId.value, o.environment.name, o.merchantAccountId.value, o.merchantOrderId.value,
                schedule.nextCheckAt, schedule.recoveryConfigVersion, at,
            ),
        )
        entityManager.flush()
    }

    override fun lock(attemptId: AttemptId): RecoverySchedule? = translate(ENTITY) {
        entityManager.lockRow(JpaRecoveryScheduleEntity::class.java, attemptId.value)?.let(::toSchedule)
    }

    override fun update(schedule: RecoverySchedule, at: Instant): Unit = translate(ENTITY) {
        val row = entityManager.lockRow(JpaRecoveryScheduleEntity::class.java, schedule.attemptId.value) ?: error("recovery schedule is not stored")
        check(row.authorizationId == schedule.authorizationId.value && row.paymentId == schedule.paymentId.value) { "schedule identity never changes" }
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

    override fun claimDue(now: Instant, limit: Int): List<RecoverySchedule> = translate(ENTITY) {
        entityManager.lockRows(JpaRecoveryScheduleEntity::class.java, rows.claimDueIds(now, limit)).map(::toSchedule)
    }

    private fun toSchedule(row: JpaRecoveryScheduleEntity) = RecoverySchedule(
        attemptId = AttemptId(row.attemptId),
        authorizationId = AuthorizationId(row.authorizationId),
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
        const val ENTITY = "authorization_recovery_schedule"
    }
}
