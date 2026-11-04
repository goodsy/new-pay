package com.paycore.admin.paymentops.application.required

import com.paycore.admin.paymentops.application.provided.TransactionSearchCriteria

/**
 * PAYMENT 관리자 거래 조회 호출 Port(TXN-OPS-001 §4 — 구현 `adapter.integration.HttpPaymentOpsGateway`: **기존** ADMIN → PAYMENT 서명 채널·같은 키·`lookup` permit·timeout·응답 상한·
 * 허용 필드 모양 검사). **DB TX 안에서 부르면 `IllegalStateException`**. 경로 변수·본문 값은 호출자가 형식 검사한 값만 넣는다. 실패는 예외가 아니라 [PaymentReadResult.Unavailable].
 */
interface PaymentReadGateway {
    fun searchPayments(criteria: TransactionSearchCriteria): PaymentReadResult

    fun paymentDetail(paymentId: String): PaymentReadResult
}

sealed interface PaymentReadResult {
    data class Found(val document: Map<String, Any?>) : PaymentReadResult {
        override fun toString(): String = "Found"
    }

    data object NotFound : PaymentReadResult

    data class Unavailable(val reason: String) : PaymentReadResult
}
