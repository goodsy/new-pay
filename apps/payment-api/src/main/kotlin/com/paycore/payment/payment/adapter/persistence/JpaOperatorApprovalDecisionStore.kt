package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.OperatorApprovalDecision
import com.paycore.payment.payment.application.required.OperatorApprovalDecisionStore
import com.paycore.payment.payment.application.required.OperatorDecisionKind
import com.paycore.payment.payment.application.required.OperatorDecisionOrigin
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorId
import com.paycore.payment.payment.domain.PaymentId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [OperatorApprovalDecisionStore] JPA 구현(ADMIN-PAYMENT-OPS-001 §4.4.4, MANDATORY TX). [append]는 `persist` + `flush` — UNIQUE(approval_id) 1062는 여기서 `PersistenceFailure.UniqueConflict`로
 * 던진다(잡지 않는다 — 호출자 TX 전체 rollback 후 재시도가 커밋된 결정을 재생). [find]는 비잠금 읽기(READ COMMITTED 최신 커밋 — 판정 권위는 UNIQUE).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaOperatorApprovalDecisionStore(
    private val rows: OperatorApprovalDecisionJpaRepository,
    private val entityManager: EntityManager,
) : OperatorApprovalDecisionStore {

    override fun find(approvalId: OperatorApprovalId): OperatorApprovalDecision? = translate(ENTITY) {
        rows.findByApproval(approvalId.value)?.let(::toDecision)
    }

    override fun append(decision: OperatorApprovalDecision): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaOperatorApprovalDecisionEntity.newRow(
                decision.approvalId.value, decision.paymentId.value, decision.kind.name, decision.rejectionCode, decision.cancellationId?.value, decision.contentHash,
                decision.amountMinor, decision.currency.value, decision.requestedBy.value, decision.approvedBy.value, decision.approvedTargetFingerprint, decision.approvedAt,
                decision.executeBefore, decision.decidedAt, decision.origin.name,
            ),
        )
        entityManager.flush()
    }

    private fun toDecision(e: JpaOperatorApprovalDecisionEntity) = OperatorApprovalDecision(
        OperatorApprovalId(e.approvalId), PaymentId(e.paymentId), OperatorDecisionKind.valueOf(e.decision), e.rejectionCode, e.cancellationId?.let(::CancellationId), e.contentHash,
        e.amountMinor, CurrencyCode(e.currency), OperatorId(e.requestedBy), OperatorId(e.approvedBy), e.approvedTargetFingerprint, e.approvedAt, e.executeBefore, e.decidedAt,
        OperatorDecisionOrigin.valueOf(e.origin),
    )

    private companion object {
        const val ENTITY = "operator_approval_decision"
    }
}
