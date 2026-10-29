package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.ProviderRequestRef
import com.paycore.payment.payment.domain.TerminationEvidence
import java.time.Instant

/** 취소 한 건의 조회 행(비권위 — 응답·재생 표시용). 거래·반환 잔액 값은 같은 읽기 TX의 한 스냅샷이다. */
data class CancellationViewRow(
    val orderKey: OrderKey,
    val paymentId: PaymentId,
    val cancellationId: CancellationId,
    val status: CancellationStatus,
    val money: Money,
    val failureEvidence: TerminationEvidence?,
    val cancelTransactionId: String?,
    val cancelApprovalNumber: String?,
    val confirmedAt: Instant?,
    val authorizedMinor: Long,
    val completedMinor: Long,
    val reservedMinor: Long,
    val forcedMinor: Long,
)

/**
 * 운영자 승인 대상 재료(비권위). `payment`에서 시작한다(ADMIN-PAYMENT-OPS-001 F-2 — 승인 없는 Payment도 행이 있다). [authorizedMoney]는 확정 승인 거래(없으면 null), 잔액이 아직 없으면
 * [hasBalance] false. [paymentStatus]는 Payment 상태 이름, [onHold]는 풀리지 않은 사건 hold가 있는가(운영자 경로를 막지 않음 — 표시용), [inFlightCancel]은 진행 중(OPEN·UNKNOWN) 취소가
 * 있는가(F-3).
 */
data class OperatorCancelTargetRow(
    val paymentId: PaymentId,
    val authorizedMoney: Money?,
    val hasBalance: Boolean,
    val completedMinor: Long,
    val reservedMinor: Long,
    val forcedMinor: Long,
    val balanceRevision: Long,
    val paymentStatus: String = "AUTHORIZED",
    val onHold: Boolean = false,
    val inFlightCancel: Boolean = false,
) {
    val currency: CurrencyCode? get() = authorizedMoney?.currency
}

/** 회복 Worker의 취소 Attempt 정보(비권위 — 상태 판정은 잠금 재판정). [routeRef]는 원 Authorization의 Route, [routeProfileVersion]은 전송 때 고정한 버전(D-CANCEL1-004). */
data class CancelAttemptViewRow(
    val attemptId: CancelAttemptId,
    val cancellationId: CancellationId,
    val state: AttemptState,
    val preparedAt: Instant,
    val providerRequest: ProviderRequestRef,
    val routeRef: String,
    val routeProfileVersion: String?,
)

/** 취소 적체 수(비권위 — gauge 전용): UNKNOWN 취소 수·가장 이른 준비 시각·이관 뒤 닫히지 않은 회복 일정 수. */
data class CancellationBacklogCounts(val unknownCancellations: Long, val oldestUnknownPreparedAt: Instant?, val escalatedOpenSchedules: Long)

/** 취소 조회 Port(CANCEL-001 §8.5 — jOOQ CQRS, 쓰기 없음, 비권위: 상태 변경은 JPA 잠금 재판정). */
interface CancellationViewReader {
    fun find(cancellationId: CancellationId): CancellationViewRow?

    /** 관리 조회(§4.7): 질의 시간 상한을 넘으면 [QueryTimedOut]. */
    fun findTarget(paymentId: PaymentId): OperatorCancelTargetRow?

    fun findAttempt(attemptId: CancelAttemptId): CancelAttemptViewRow?

    fun backlog(): CancellationBacklogCounts
}
