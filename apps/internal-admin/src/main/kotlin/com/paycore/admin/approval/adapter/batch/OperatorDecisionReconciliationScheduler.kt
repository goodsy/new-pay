package com.paycore.admin.approval.adapter.batch

import com.paycore.admin.approval.application.provided.ReconcileOperatorDecisionsUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * PAYMENT 결정 ↔ ADMIN 승인 대사 job(ADMIN-PAYMENT-OPS-001 §4.17, D-OPS-008 — provided만 호출, Demo 5분). 여러 인스턴스가 돌아도 발견은 UNIQUE로 한 번만,
 * checkpoint는 뒤로 가지 않는다. `paycore.admin.payment-ops.reconciliation.enabled=false`면 등록하지 않는다(시험은 Use Case 직접 호출).
 */
@Component
@ConditionalOnProperty(prefix = "paycore.admin.payment-ops.reconciliation", name = ["enabled"], havingValue = "true")
class OperatorDecisionReconciliationScheduler(private val reconciliation: ReconcileOperatorDecisionsUseCase) {

    @Scheduled(fixedDelayString = "\${paycore.admin.payment-ops.reconciliation.fixed-delay}")
    fun run() {
        reconciliation.reconcile()
    }
}
