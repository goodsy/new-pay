package com.paycore.admin.paymentops.application.required

import com.paycore.admin.paymentops.application.provided.CallPurpose
import com.paycore.admin.paymentops.application.provided.ExecutionResult
import com.paycore.admin.paymentops.application.provided.TargetResult

/**
 * PAYMENT 내부 API 호출 Port(구현 `adapter.integration.HttpPaymentOpsGateway` — 서명·permit·timeout·응답 상한·분류). **DB TX 안에서 부르면 `IllegalStateException`**(§4.7).
 * 경로 변수는 형식 검사 뒤에만 넣는다(§4.2 — 인코딩이 필요한 문자가 생기지 않는다). 실패는 예외가 아니라 결과 값으로 돌려준다.
 */
interface PaymentOpsGateway {
    fun target(paymentId: String, purpose: CallPurpose): TargetResult

    fun execute(approvalId: String, body: ByteArray): ExecutionResult

    fun decision(approvalId: String): com.paycore.admin.paymentops.application.provided.DecisionResult

    fun confirmDeadline(approvalId: String, body: ByteArray): com.paycore.admin.paymentops.application.provided.DecisionResult

    fun decisionsAfter(watermark: Long): com.paycore.admin.paymentops.application.provided.DecisionPage
}
