package com.paycore.admin.archfixture.application

import com.paycore.admin.paymentops.application.provided.DecisionResult
import com.paycore.admin.paymentops.application.provided.ExecutionResult
import com.paycore.admin.paymentops.application.provided.PaymentOpsClient

/**
 * ArchUnit 위반 fixture(S7 리뷰 L-2 — §4.17 "대사는 상태를 바꾸지 않는다", 시험 소스 전용, bean 아님): 대사 클래스가 발견을 "고치려고" PAYMENT에 실행 POST·기한 확인을 보낸다.
 */
@Suppress("unused")
class DecisionReconciliationResendFixture(private val client: PaymentOpsClient) {
    fun resend(approvalId: String, body: ByteArray): ExecutionResult = client.execute(approvalId, body)

    fun confirm(approvalId: String, body: ByteArray): DecisionResult = client.confirmDeadline(approvalId, body)
}
