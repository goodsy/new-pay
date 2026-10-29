package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.PaymentId

/**
 * 운영자 보정 취소의 승인 대상 재료 조회(ADMIN-PAYMENT-OPS-001 §4.3 — 진입점은 ADMIN 채널 `OperatorCancelTargetController`만, ArchUnit). 비권위 읽기 — 실행 때 PAYMENT가 잠금 아래 다시
 * 검사한다. 저장소 실패·질의 시간 초과는 [OperatorQueryFailure].
 */
interface OperatorCancelTargetQuery {
    fun find(paymentId: String): OperatorCancelTargetView?
}

/**
 * [cancellable]은 확정 승인 거래가 있는가(D-CANCEL-011), [authorizedMinor]는 원 승인액(없으면 null), [cancellableMinor]는 domain 식 max(0, 승인 − 완료 − 예약 − FORCED)(F-1, D-CPQ-005),
 * [balanceRevision]은 현재 반환 잔액 revision(아직 없으면 0). [onHold]는 표시용(운영자 경로를 막지 않음 — D-CANCEL-001), [inFlightCancel]은 진행 중 취소(요청 advisory — I-32).
 */
data class OperatorCancelTargetView(
    val paymentId: PaymentId,
    val paymentStatus: String,
    val cancellable: Boolean,
    val currency: CurrencyCode?,
    val authorizedMinor: Long?,
    val cancellableMinor: Long,
    val balanceRevision: Long,
    val onHold: Boolean,
    val inFlightCancel: Boolean,
)
