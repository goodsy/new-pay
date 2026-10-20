package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.ReturnStatus
import java.time.Instant

/**
 * 공개 결제 상태(AC1 §5.1 Payment 요약 상태를 confirm 응답 어휘로 — CP-QUERY-001 §5.1): AUTHORIZING = [IN_PROGRESS], UNKNOWN은 그대로("확인 중" — 범위 §3-8, CQ-12).
 */
enum class PaymentViewStatus { REQUESTED, IN_PROGRESS, UNKNOWN, AUTHORIZED, FAILED, EXPIRED }

/** 취소 개시자(D-CPQ-002): 운영자 보정 취소는 개시자 종류만 보인다(신원·사유·approvalId·목적 없음). */
enum class CancellationInitiator { MERCHANT, OPERATOR }

/** 반환(취소) 요약(CC §4.3): 승인액·완료 반환·진행 중 반환·반환 가능액(`max(0, …)` — F-1)·반환 상태. FORCED·초과는 없다(D-CPQ-001). */
data class ReturnSummaryView(
    val authorizedMinor: Long,
    val cancelledMinor: Long,
    val inProgressMinor: Long,
    val cancellableMinor: Long,
    val returnStatus: ReturnStatus,
)

/** 취소 이력 한 건(CC §8 취소 응답과 같은 필드 — CQ-15). COMPLETED면 거래·승인번호(있으면)·완료 시각, FAILED면 분류. */
data class CancellationHistoryItem(
    val cancellationId: CancellationId,
    val initiator: CancellationInitiator,
    val merchantCancelId: MerchantCancelId?,
    val status: CancellationViewStatus,
    val money: Money,
    val requestedAt: Instant,
    val cancelTransactionId: String?,
    val cancelApprovalNumber: String?,
    val cancelledAt: Instant?,
    val failureCategory: CancelFailureCategory?,
) {
    init {
        require((initiator == CancellationInitiator.MERCHANT) == (merchantCancelId != null)) { "a merchant cancel id exists exactly for merchant cancels" }
        require((status == CancellationViewStatus.COMPLETED) == (cancelTransactionId != null && cancelledAt != null)) { "a cancel transaction exists exactly for COMPLETED" }
        require((status == CancellationViewStatus.FAILED) == (failureCategory != null)) { "a failure category exists exactly for FAILED" }
    }
}

/**
 * 결제 현재 상태(CP-QUERY-001 §4.4). 카드번호·인증 증거·기관 원문·기관 계약계정·내부 버전·운영자 정보·paymentKey는 없다.
 * - [authorization]: Authorization이 없으면 null. 승인 terms는 공개 상태가 AUTHORIZED일 때만.
 * - [returnSummary]·[cancellations]: AUTHORIZED일 때만(D-CPQ-002 — P1(FAILED/EXPIRED)의 늦은 승인·운영자 취소는 숨긴다).
 * - [onHold]: 그 Payment 자신의 활성 사고 보류(D-CPQ-001 — 사유 없음).
 */
data class PaymentStatusView(
    val paymentId: PaymentId,
    val merchantOrderId: MerchantOrderId,
    val money: Money,
    val createdAt: Instant,
    val status: PaymentViewStatus,
    val failureCategory: FailureCategory?,
    val onHold: Boolean,
    val authorization: AuthorizationView?,
    val returnSummary: ReturnSummaryView?,
    val cancellations: List<CancellationHistoryItem>,
) {
    init {
        require((status == PaymentViewStatus.FAILED) == (failureCategory != null)) { "a failure category exists exactly for FAILED" }
        // 검토 #1 n-6: 요약 조건은 아래 한 줄(⇔)이 맡고, 이력 조건만 따로 둔다(중복 단언 제거).
        require(status == PaymentViewStatus.AUTHORIZED || cancellations.isEmpty()) { "history is shown only for AUTHORIZED" }
        require((status == PaymentViewStatus.AUTHORIZED) == (returnSummary != null)) { "an authorized payment has a return summary" }
    }
}
