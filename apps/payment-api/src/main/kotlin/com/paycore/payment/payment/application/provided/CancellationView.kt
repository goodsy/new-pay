package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.ReturnStatus
import java.time.Instant

/** 취소 응답 상태(CANCEL-001 §4.1 — CC §8). OPEN = IN_PROGRESS. */
enum class CancellationViewStatus { IN_PROGRESS, UNKNOWN, COMPLETED, FAILED }

/** FAILED 취소의 고정 분류(CC §8): 기관 거절·미전송 확정·기관 무효과 확정. */
enum class CancelFailureCategory { DECLINED, NOT_SENT, NO_EFFECT }

/**
 * 취소 현재 상태(CANCEL-001 §4.1 응답 본문 — 재생도 현재 상태, CC-05). COMPLETED면 취소 거래 ID·기관 취소 승인번호(있으면)·PG 확정 시각, FAILED면 [failureCategory]. 반환 후
 * [cancellableAmountMinor]·[returnStatus]. 카드번호·기관 원문·내부 정책 버전·기관 계약계정 없음.
 */
data class CancellationView(
    val paymentId: PaymentId,
    val cancellationId: CancellationId,
    val status: CancellationViewStatus,
    val money: Money,
    val cancelTransactionId: String?,
    val cancelApprovalNumber: String?,
    val cancelledAt: Instant?,
    val failureCategory: CancelFailureCategory?,
    val cancellableAmountMinor: Long,
    val returnStatus: ReturnStatus,
)
