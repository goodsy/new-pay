package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.OperatorCancelRejection
import com.paycore.payment.payment.application.required.OperatorCancelRejectionStore
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorId
import com.paycore.payment.payment.domain.PaymentId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [OperatorCancelRejectionStore] JPA 구현(D-CANCEL1-006·008, MANDATORY TX). 거절마다 INSERT 한 번(`@Immutable` Entity + runtime 권한 SELECT·INSERT) — 중복 제거 UNIQUE가 없어 경합 1062가
 * 없다. 입력 값 타입이 길이·형식을 먼저 막으므로(PaymentId·OperatorId·OperatorApprovalId ≤ 64, 사유 코드) 저장 단계 1406·3819는 결함이다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaOperatorCancelRejectionStore(
    private val rows: OperatorCancelRejectionJpaRepository,
    private val entityManager: EntityManager,
) : OperatorCancelRejectionStore {

    override fun append(rejection: OperatorCancelRejection): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaOperatorCancelRejectionEntity.newRow(
                rejection.approvalId.value, rejection.paymentId.value, rejection.requestedBy.value, rejection.approvedBy.value, rejection.reason, rejection.rejectedAt,
            ),
        )
        entityManager.flush()
    }

    override fun findByApproval(approvalId: OperatorApprovalId): List<OperatorCancelRejection> = translate(ENTITY) {
        rows.findByApproval(approvalId.value).map {
            OperatorCancelRejection(OperatorApprovalId(it.approvalId), PaymentId(it.paymentId), OperatorId(it.requestedBy), OperatorId(it.approvedBy), it.rejectionReason, it.rejectedAt)
        }
    }

    private companion object {
        const val ENTITY = "operator_cancel_rejection"
    }
}
