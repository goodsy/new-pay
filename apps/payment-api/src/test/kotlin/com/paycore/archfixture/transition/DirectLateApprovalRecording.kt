package com.paycore.archfixture.transition

import com.paycore.payment.payment.domain.AuthorizationTransaction
import com.paycore.payment.payment.domain.PaymentAuthorization

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): domain 밖에서 RECON 늦은 승인 거래 기록 전이를 부른다(D-CANCEL-011 — 기록 Use Case는 RECON-CONTRACT-001이
 * domain 서비스로 정한다). 대상 = PaymentAuthorization.recordLateApprovalByReconciliation.
 */
class DirectLateApprovalRecording {
    fun bypass(authorization: PaymentAuthorization, transaction: AuthorizationTransaction) {
        authorization.recordLateApprovalByReconciliation(transaction)
    }
}
