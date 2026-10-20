package com.paycore.archfixture.statusreader

import com.paycore.payment.payment.application.required.PaymentStatusReader
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.PaymentId

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 명령 서비스가 비권위 결제 상태 조회 Port로 판정한다 — 조회 view는 승인·취소·한도 판정에 쓰면 안 된다(CP-QUERY-001 CQ-06, 원칙 §4).
 */
class CommandServiceUsingStatusReader(private val reader: PaymentStatusReader) {
    fun mayCancel(environment: Environment, mid: MerchantAccountId, paymentId: PaymentId): Boolean = reader.find(environment, mid, paymentId) != null
}
