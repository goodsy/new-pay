package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.PaymentId

/**
 * 운영자 보정 취소의 승인 대상 재료 조회(CANCEL-001 §5.3 — 진입점 없이 Use Case 시험으로만, 내부 조회 API는 ADMIN-BOOT-001). 비권위 읽기 — 실행 때 PAYMENT가 잠금 아래 다시 검사한다.
 */
interface OperatorCancelTargetQuery {
    fun find(paymentId: String): OperatorCancelTargetView?
}

/** [cancellable]은 확정 승인 거래가 있는가(D-CANCEL-011), [cancellableMinor]·[balanceRevision]은 현재 반환 잔액(아직 없으면 원 승인액·0). */
data class OperatorCancelTargetView(
    val paymentId: PaymentId,
    val cancellable: Boolean,
    val currency: CurrencyCode?,
    val cancellableMinor: Long,
    val balanceRevision: Long,
)
