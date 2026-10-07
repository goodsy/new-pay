package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentRejection
import com.paycore.payment.payment.application.provided.IdempotencyRecordId
import com.paycore.payment.payment.application.provided.RequestFingerprint
import com.paycore.payment.payment.application.required.ControlAcquisition
import com.paycore.payment.payment.application.required.IdempotencyRecordStore
import com.paycore.payment.payment.application.required.IdempotencyReservation
import com.paycore.payment.payment.application.required.IdempotencyReservationOutcome
import com.paycore.payment.payment.application.required.IdempotencyScope
import com.paycore.payment.payment.application.required.OrderPaymentControlStore
import com.paycore.payment.payment.application.required.OutboxEvent
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentCreationAttributes
import com.paycore.payment.payment.application.required.PaymentCreationRecord
import com.paycore.payment.payment.application.required.PaymentStore
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoredIdempotencyRecord
import com.paycore.payment.payment.application.required.StoredRequestResult
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentId
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/*
 * application.required Port 구현(JPA). 공통 관례(CP-DB-001, ARCHITECTURE-CONVENTIONS §9):
 * - `@Transactional(MANDATORY)`: Port는 application이 연 로컬 TX 안에서만 동작한다(TX 경계는 application이 소유).
 * - 쓰기 메서드는 끝에서 flush한다: 제약 위반·version 충돌을 commit 시점이 아니라 Port 안에서 드러내 [PersistenceFailure]로 바꾼다.
 * - 변경 감지(dirty checking): 잠가 읽은 Entity는 영속성 컨텍스트가 관리하므로 필드만 바꾸면 flush 때 UPDATE가 나간다(save 호출 불필요).
 *   domain 객체는 추적 대상이 아니라서 Store가 Mapper로 바뀐 값을 Entity에 옮긴다. 옮기기 전에 기대 version을 비교한다.
 * - flush ≠ commit: flush는 SQL을 이 TX의 연결로 보내 제약 검사·행 잠금을 일으키지만 결과는 commit 전까지 다른 TX에 보이지 않고
 *   rollback되면 사라진다.
 */

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPaymentStore(
    private val payments: PaymentJpaRepository,
    private val evidence: PaymentTerminationEvidenceJpaRepository,
    private val entityManager: EntityManager,
) : PaymentStore {

    override fun insertNew(payment: Payment, attributes: PaymentCreationAttributes): Long = translate(ENTITY) {
        val row = PaymentPersistenceMapper.newPaymentRow(payment, attributes)
        // persist(새 행 INSERT)만 쓴다. Spring Data save는 version이 있으면 merge(덮어쓰기)로 바뀔 수 있다.
        entityManager.persist(row)
        entityManager.flush()
        checkNotNull(row.version)
    }

    override fun lock(paymentId: PaymentId): Versioned<Payment>? = translate(ENTITY) {
        payments.lockById(paymentId.value)?.let { row ->
            Versioned(PaymentPersistenceMapper.toPayment(row, evidence.findByPaymentIdOrdered(row.paymentId)), checkNotNull(row.version))
        }
    }

    override fun update(payment: Payment, expectedVersion: Long): Long = translate(ENTITY) {
        val row = entityManager.find(JpaPaymentEntity::class.java, payment.id.value)
            ?: error("payment ${payment.id.value} is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        PaymentPersistenceMapper.requireSameIdentity(payment, row)

        val stored = evidence.findByPaymentIdOrdered(row.paymentId).map { it.evidence }
        val current = payment.terminationEvidence.map { it.name }
        check(current.size >= stored.size && current.subList(0, stored.size) == stored) {
            "termination evidence of ${row.paymentId} is append-only"
        }
        val appended = current.drop(stored.size)
        val statusChanged = row.status != payment.status.name

        row.status = payment.status.name
        appended.forEachIndexed { i, value ->
            entityManager.persist(JpaPaymentTerminationEvidenceEntity.newRow(row.paymentId, stored.size + i, value))
        }
        // 자식 행만 늘어도 Aggregate version을 올린다(동시에 근거를 덧붙이는 다른 TX가 version으로 충돌하게).
        if (!statusChanged && appended.isNotEmpty()) entityManager.lock(row, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        entityManager.flush()
        checkNotNull(row.version)
    }

    override fun findCreationRecord(paymentId: PaymentId): PaymentCreationRecord? = translate(ENTITY) {
        payments.findById(paymentId.value).orElse(null)?.let(PaymentPersistenceMapper::toCreationRecord)
    }

    private companion object {
        const val ENTITY = "payment"
    }
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaOrderPaymentControlStore(
    private val controls: OrderPaymentControlJpaRepository,
    private val holds: IncidentHoldJpaRepository,
    private val entityManager: EntityManager,
) : OrderPaymentControlStore {

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

        val stored = holds.findByOrder(key.environment, key.merchantAccountId, key.merchantOrderId).map { it.paymentId to it.reason }
        val current = control.incidentHolds.map { it.paymentId.value to it.reason.name }
        check(current.size >= stored.size && current.subList(0, stored.size) == stored) { "incident holds are append-only" }
        val appended = current.drop(stored.size)

        val rootChanged = row.currentPaymentId != control.currentPaymentId.value || row.generation != control.generation ||
            row.authorizedPaymentId != control.authorizedPaymentId?.value
        if (rootChanged || appended.isNotEmpty()) {
            row.currentPaymentId = control.currentPaymentId.value
            row.generation = control.generation
            row.authorizedPaymentId = control.authorizedPaymentId?.value
            val timestampChanged = row.updatedAt != at
            row.updatedAt = at
            appended.forEach { (paymentId, reason) -> entityManager.persist(JpaIncidentHoldEntity.newRow(key, paymentId, reason, at)) }
            if (!rootChanged && !timestampChanged) entityManager.lock(row, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        }
        entityManager.flush()
        checkNotNull(row.version)
    }

    private fun toDomain(row: JpaOrderPaymentControlEntity): OrderPaymentControl {
        val k = row.orderKey
        return PaymentPersistenceMapper.toControl(row, holds.findByOrder(k.environment, k.merchantAccountId, k.merchantOrderId))
    }

    private companion object {
        const val ENTITY = "order_payment_control"
    }
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaIdempotencyRecordStore(
    private val requests: IdempotencyRequestJpaRepository,
    private val entityManager: EntityManager,
) : IdempotencyRecordStore {

    override fun reserveOrLock(reservation: IdempotencyReservation): IdempotencyReservationOutcome = translate(ENTITY) {
        val s = reservation.scope
        requests.insertPendingIfAbsent(
            s.environment.name, s.merchantAccountId.value, s.operation.code, s.key.value, reservation.recordId.value,
            reservation.requestFingerprint.value, reservation.createdAt, reservation.expiresAt,
        )
        val row = lockedRow(s)
        if (row.requestRecordId == reservation.recordId.value && row.resultKind == PENDING) {
            IdempotencyReservationOutcome.Reserved
        } else {
            IdempotencyReservationOutcome.Existing(toStored(row))
        }
    }

    override fun renewExpired(reservation: IdempotencyReservation): Unit = translate(ENTITY) {
        val row = lockedRow(reservation.scope)
        // 삭제 전 만료 행만 새 요청으로 갱신한다. 보존 기간 안의 기록은 덮어쓰지 않는다(D-CPC-004).
        check(!reservation.createdAt.isBefore(row.expiresAt)) { "a live idempotency record cannot be renewed" }
        row.requestRecordId = reservation.recordId.value
        row.requestFingerprint = reservation.requestFingerprint.value
        row.resultKind = PENDING
        row.resultCode = null
        row.resultPaymentId = null
        row.createdAt = reservation.createdAt
        row.expiresAt = reservation.expiresAt
        row.updatedAt = reservation.createdAt
        entityManager.flush()
    }

    override fun complete(scope: IdempotencyScope, result: StoredRequestResult, completedAt: Instant): Unit = translate(ENTITY) {
        val row = lockedRow(scope)
        check(row.resultKind == PENDING) { "idempotency record already has a result" }
        when (result) {
            is StoredRequestResult.Succeeded -> {
                row.resultKind = SUCCESS
                row.resultCode = result.outcome.name
                row.resultPaymentId = result.paymentId.value
            }
            is StoredRequestResult.Rejected -> {
                row.resultKind = REJECTED
                row.resultCode = result.rejection.name
                row.resultPaymentId = result.referencePaymentId.value
            }
        }
        row.updatedAt = completedAt
        entityManager.flush()
    }

    /** 같은 TX에서 이미 잠갔으면 영속성 컨텍스트의 같은 Entity가 돌아온다(추가 SQL 잠금은 재진입). */
    private fun lockedRow(s: IdempotencyScope): JpaIdempotencyRequestEntity =
        requests.lockByScope(s.environment.name, s.merchantAccountId.value, s.operation.code, s.key.value)
            ?: error("idempotency record must exist after insert-if-absent")

    private fun toStored(row: JpaIdempotencyRequestEntity): StoredIdempotencyRecord =
        StoredIdempotencyRecord(
            recordId = IdempotencyRecordId(row.requestRecordId),
            requestFingerprint = RequestFingerprint(row.requestFingerprint),
            result = when (row.resultKind) {
                PENDING -> null
                SUCCESS -> StoredRequestResult.Succeeded(CreatePaymentOutcome.valueOf(checkNotNull(row.resultCode)), PaymentId(checkNotNull(row.resultPaymentId)))
                REJECTED -> StoredRequestResult.Rejected(CreatePaymentRejection.valueOf(checkNotNull(row.resultCode)), PaymentId(checkNotNull(row.resultPaymentId)))
                else -> error("unknown idempotency result kind")
            },
            createdAt = row.createdAt,
            expiresAt = row.expiresAt,
        )

    private companion object {
        const val ENTITY = "idempotency_request"
        const val PENDING = "PENDING"
        const val SUCCESS = "SUCCESS"
        const val REJECTED = "REJECTED"
    }
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaOutboxStore(private val entityManager: EntityManager) : OutboxStore {

    override fun append(event: OutboxEvent): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaOutboxEventEntity.pending(
                eventId = event.eventId.value,
                effectKey = event.effectKey,
                eventType = event.eventType,
                schemaVersion = event.schemaVersion,
                eventCategory = event.eventCategory,
                producer = event.producer,
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                aggregateVersion = event.aggregateVersion,
                occurredAt = event.occurredAt,
                correlationId = event.correlationId,
                causationId = event.causationId,
                payload = OutboxPayloadJson.serialize(event.payload),
                createdAt = event.occurredAt,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val ENTITY = "outbox_event"
    }
}
