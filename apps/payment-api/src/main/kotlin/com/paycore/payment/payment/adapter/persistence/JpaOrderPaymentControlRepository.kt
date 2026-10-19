package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.ControlAcquisition
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaOrderPaymentControlRepository(
    private val controls: OrderPaymentControlJpaRepository,
    private val holds: IncidentHoldJpaRepository,
    private val releases: IncidentHoldReleaseJpaRepository,
    private val entityManager: EntityManager,
) : OrderPaymentControlRepository {

    override fun openOrLock(initial: OrderPaymentControl, at: Instant): ControlAcquisition = translate(ENTITY) {
        require(initial.generation == 1L && initial.authorizedPaymentId == null && initial.incidentHolds.isEmpty()) {
            "only a freshly opened control can be inserted"
        }
        val key = PaymentPersistenceMapper.orderKeyColumns(initial.orderKey)
        // 1) PK에 먼저 INSERT(없으면 삽입, 있으면 그 행의 잠금을 기다림) 2) 잠가 읽기. SELECT 후 INSERT가 아니다.
        controls.insertIfAbsent(
            key.environment, key.merchantAccountId, key.merchantOrderId, initial.fixedIntent.value, initial.currentPaymentId.value, 1L, at,
        )
        val row = controls.lockById(key) ?: error("order payment control must exist after insert-if-absent")
        val restored = Versioned(toDomain(row), checkNotNull(row.version))
        // 이 TX가 삽입했는지: 새 Payment ID(서버 생성, 재사용 없음)가 현재 점유자이고 아직 한 번도 바뀌지 않은 행.
        val insertedHere = row.currentPaymentId == initial.currentPaymentId.value && row.generation == 1L && row.version == 0L &&
            row.fixedIntentFingerprint == initial.fixedIntent.value
        if (insertedHere) ControlAcquisition.Opened(restored) else ControlAcquisition.Existing(restored)
    }

    override fun lock(orderKey: OrderKey): Versioned<OrderPaymentControl>? = translate(ENTITY) {
        controls.lockById(PaymentPersistenceMapper.orderKeyColumns(orderKey))?.let { Versioned(toDomain(it), checkNotNull(it.version)) }
    }

    override fun update(control: OrderPaymentControl, expectedVersion: Long, at: Instant): Long = translate(ENTITY) {
        val key = PaymentPersistenceMapper.orderKeyColumns(control.orderKey)
        val row = entityManager.find(JpaOrderPaymentControlEntity::class.java, key)
            ?: error("order payment control is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(row.fixedIntentFingerprint == control.fixedIntent.value) { "fixed intent of an order never changes" }
        check(control.generation >= row.generation) { "slot generation never decreases" }
        check(row.authorizedPaymentId == null || row.authorizedPaymentId == control.authorizedPaymentId?.value) {
            "authorization history is never erased"
        }

        val storedRows = holds.findByOrder(key.environment, key.merchantAccountId, key.merchantOrderId)
        val stored = storedRows.map(PaymentPersistenceMapper::toHold)
        val current = control.incidentHolds
        // hold 식별(paymentId, reason, occurrence)과 여는 관찰(H-1)이 저장된 그대로 앞부분에 있어야 한다(append-only).
        check(current.size >= stored.size && current.subList(0, stored.size).zip(stored).all { (c, s) -> c == s && c.sourceObservation == s.sourceObservation }) {
            "incident holds are append-only"
        }
        val appended = current.drop(stored.size)
        // AUTH-001 n-4: 해제 기록 쓰기 경로는 이 작업에 없다(RECON-CONTRACT-001이 권한과 함께 추가) — 저장 안 된 해제는 거부한다(fail-closed).
        val storedReleases = if (storedRows.isEmpty()) 0 else releases.findByHoldIds(storedRows.mapNotNull { it.id }).size
        check(control.incidentHoldReleases.size == storedReleases) { "incident hold releases are written only by the reconciliation release use case" }

        val rootChanged = row.currentPaymentId != control.currentPaymentId.value || row.generation != control.generation ||
            row.authorizedPaymentId != control.authorizedPaymentId?.value
        if (rootChanged || appended.isNotEmpty()) {
            row.currentPaymentId = control.currentPaymentId.value
            row.generation = control.generation
            row.authorizedPaymentId = control.authorizedPaymentId?.value
            val timestampChanged = row.updatedAt != at
            row.updatedAt = at
            appended.forEach { h ->
                val source = h.sourceObservation
                entityManager.persist(
                    JpaIncidentHoldEntity.newRow(
                        key, h.paymentId.value, h.reason.name, h.occurrence, source?.key?.attemptId?.value, source?.key?.source?.name, source?.key?.sourceResultId,
                        source?.variant, at,
                    ),
                )
            }
            if (!rootChanged && !timestampChanged) entityManager.lock(row, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        }
        entityManager.flush()
        checkNotNull(row.version)
    }

    private fun toDomain(row: JpaOrderPaymentControlEntity): OrderPaymentControl {
        val k = row.orderKey
        val holdRows = holds.findByOrder(k.environment, k.merchantAccountId, k.merchantOrderId)
        val releaseRows = if (holdRows.isEmpty()) emptyList() else releases.findByHoldIds(holdRows.mapNotNull { it.id })
        return PaymentPersistenceMapper.toControl(row, holdRows, releaseRows)
    }

    private companion object {
        const val ENTITY = "order_payment_control"
    }
}
