package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.PaymentCreationAttributes
import com.paycore.payment.payment.application.required.PaymentCreationRecord
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentId
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/*
 * application.required Port 구현(JPA). 공통 관례(CP-DB-001, ARCHITECTURE-CONVENTIONS §9):
 * - `@Transactional(MANDATORY)`: Port는 application이 연 로컬 TX 안에서만 동작한다(TX 경계는 application이 소유).
 * - 쓰기 메서드는 끝에서 flush한다: 제약 위반·version 충돌을 commit 시점이 아니라 Port 안에서 드러내 [PersistenceFailure]로 바꾼다.
 * - 변경 감지(dirty checking): 잠가 읽은 Entity는 영속성 컨텍스트가 관리하므로 필드만 바꾸면 flush 때 UPDATE가 나간다(save 호출 불필요).
 *   domain 객체는 추적 대상이 아니라서 Port 구현(이 클래스 등 `Jpa*Repository`)이 Mapper로 바뀐 값을 Entity에 옮긴다. 옮기기 전에 기대 version을 비교한다.
 * - flush ≠ commit: flush는 SQL을 이 TX의 연결로 보내 제약 검사·행 잠금을 일으키지만 결과는 commit 전까지 다른 TX에 보이지 않고
 *   rollback되면 사라진다.
 */

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPaymentRepository(
    private val payments: PaymentJpaRepository,
    private val evidence: PaymentTerminationEvidenceJpaRepository,
    private val authorizations: PaymentAuthorizationJpaRepository,
    private val entityManager: EntityManager,
) : PaymentRepository {

    override fun insertNew(payment: Payment, attributes: PaymentCreationAttributes): Long = translate(ENTITY) {
        val row = PaymentPersistenceMapper.newPaymentRow(payment, attributes)
        // persist(새 행 INSERT)만 쓴다. Spring Data save는 version이 있으면 merge(덮어쓰기)로 바뀔 수 있다.
        entityManager.persist(row)
        entityManager.flush()
        checkNotNull(row.version)
    }

    override fun lock(paymentId: PaymentId): Versioned<Payment>? = translate(ENTITY) {
        entityManager.lockRow(JpaPaymentEntity::class.java, paymentId.value)?.let { row ->
            val hasAuthorization = authorizations.findIdByPaymentId(row.paymentId) != null
            Versioned(PaymentPersistenceMapper.toPayment(row, evidence.findByPaymentIdOrdered(row.paymentId), hasAuthorization), checkNotNull(row.version))
        }
    }

    override fun update(payment: Payment, expectedVersion: Long): Long = translate(ENTITY) {
        val row = entityManager.find(JpaPaymentEntity::class.java, payment.id.value)
            ?: error("payment ${payment.id.value} is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        PaymentPersistenceMapper.requireSameIdentity(payment, row)

        val stored = evidence.findByPaymentIdOrdered(row.paymentId).map(PaymentPersistenceMapper::toEvidenceRecord)
        val current = payment.terminationEvidenceRecords
        check(current.size >= stored.size && current.subList(0, stored.size) == stored) {
            "termination evidence of ${row.paymentId} is append-only"
        }
        val appended = current.drop(stored.size)
        val statusChanged = row.status != payment.status.name

        row.status = payment.status.name
        PaymentPersistenceMapper.requireSameCheckoutWindow(payment, row)
        appended.forEachIndexed { i, record ->
            // AUTH-001 rev 0.3 m-6: 새 근거는 domain이 출처를 강제한다(출처 없는 근거는 복원된 legacy 행뿐이라 여기 오지 않는다).
            val source = checkNotNull(record.source) { "new termination evidence of ${row.paymentId} has no source" }
            entityManager.persist(
                JpaPaymentTerminationEvidenceEntity.newRow(row.paymentId, stored.size + i, record.evidence.name, source.kind.name, source.ref, source.recordedAt, source.recordedBy),
            )
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
