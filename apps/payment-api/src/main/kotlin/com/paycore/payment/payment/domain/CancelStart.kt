package com.paycore.payment.payment.domain

import java.time.Instant

/**
 * 취소 시작 입력(CANCEL-CONTRACT-001 §4.4). application이 TX1에서 잠금 순서대로 읽어 넘긴다: control → Payment → Authorization → 반환 잔액(§9.1).
 * [authorization]은 승인 operation이 없으면(REQUESTED) null, [balance]는 첫 취소면 null(CC-02 지연 개설). 신규 승인 권리·일 한도는 입력이 아니다
 * (K-3, D-AUTH-007). 같은 업무 키의 기존 취소 조회(D-CANCEL-002·CC-12)는 application이 이 연산 전에 한다([PaymentCancellation.compareRequest]).
 */
data class CancelStartCommand(
    val control: OrderPaymentControl,
    val payment: Payment,
    val authorization: PaymentAuthorization?,
    val balance: ReturnBalance?,
    val money: Money,
    val initiator: CancelInitiator,
    val capability: CancelCapabilityResolution,
    val cancellationId: CancellationId,
    val attemptId: CancelAttemptId,
    val providerRequest: ProviderRequestRef,
    val at: Instant,
)

/** 취소 시작 업무 거절(키 결과로 저장·재생은 application — D-CPC-007 선례). 외부 code 매핑은 명세 §8. */
enum class CancelStartRejection {
    /** 확정 승인 거래가 없다(REQUESTED·AUTHORIZING·UNKNOWN·FAILED·EXPIRED). */
    PAYMENT_NOT_CANCELLABLE,

    /** 이 Payment에 활성 incident hold가 있다 — 가맹점 취소 거절(D-CANCEL-001·006). */
    ORDER_ON_HOLD,

    /** 결과가 확정되지 않은 취소가 있다(D-CANCEL-003). */
    CANCEL_IN_PROGRESS,
    CURRENCY_MISMATCH,
    ALREADY_FULLY_CANCELLED,
    AMOUNT_EXCEEDS_BALANCE,
    PARTIAL_CANCEL_NOT_SUPPORTED,

    /** 원 경로 프로파일·Capability를 해석할 수 없다(fail-closed, 저장 없음 — 503). */
    CAPABILITY_UNAVAILABLE,

    /** 운영자 보정 취소 2인 승인 재검사(D-CANCEL-005, D-ADMIN-007, review m-3). */
    OPERATOR_SAME_PERSON,
    OPERATOR_REASON_MISSING,
    OPERATOR_BASIS_MISSING,

    /** 승인한 대상(Payment·Money·잔액 revision)이 실행 시점과 다르다. */
    OPERATOR_TARGET_STALE,
}

sealed interface CancelStartResult {
    /** [balanceOpened]이면 [balance]는 이 연산이 새로 연 잔액이다(application이 INSERT). */
    data class Started(val cancellation: PaymentCancellation, val balance: ReturnBalance, val balanceOpened: Boolean) : CancelStartResult

    data class Rejected(val reason: CancelStartRejection) : CancelStartResult
}

/**
 * 취소 시작 단일 domain 연산(CANCEL-CONTRACT-001 §4.4, T4). 모든 검사를 먼저 하고 통과할 때만 함께 바꾼다: [PaymentCancellation] OPEN(Attempt PREPARED)
 * + (필요하면 [ReturnBalance] 개설) + 반환 예약 RESERVED. 거절·결함이면 어떤 Aggregate도 바뀌지 않는다.
 *
 * 검사 순서: 입력 정합성(결함은 [IllegalArgumentException]) → 대상 상태 → incident hold(이 Payment의 모든 활성 hold, 가맹점만 — D-CANCEL-001·006,
 * 정산 말미 D) → 진행 중 취소(D-CANCEL-003) → 통화·반환 가능 금액(§4.3) → Capability(§4.2) → 운영자 2인 승인 재검사·승인 대상 버전(D-CANCEL-005).
 * PG 자체 취소 기한은 없다(D-CANCEL-004). Payment 상태·주문 승인 이력·일 한도는 바꾸지 않는다(K-12, D-AUTH-007).
 * main 코드에서 PaymentCancellation.open·ReturnBalance.reserve는 이 객체만 부른다(ArchUnit).
 */
object CancelStart {

    fun start(command: CancelStartCommand): CancelStartResult = with(command) {
        require(control.orderKey == payment.orderKey) { "control must be the payment's order" }
        require(authorization == null || authorization.paymentId == payment.id) { "authorization must belong to the payment" }
        if (balance != null) {
            require(authorization != null && balance.paymentId == payment.id && balance.authorizationId == authorization.id) {
                "return balance must belong to the payment's authorization"
            }
            require(balance.reservationOf(cancellationId) == null) { "cancellation id is already reserved" }
        }
        require(providerRequest.environment == payment.orderKey.environment) { "provider request environment must match the order" }

        // D-CANCEL-011: 가맹점 취소는 Payment·Authorization AUTHORIZED, 운영자 보정 취소는 Authorization에 확정 승인 거래가 있으면 된다(RECON이 기록한 늦게 확정된 P1 —
        // Payment는 FAILED/EXPIRED 종료 상태 유지).
        val original = authorization?.transaction
        val merchantCancellable = payment.status == PaymentStatus.AUTHORIZED && authorization?.status == AuthorizationStatus.AUTHORIZED && original != null
        val operatorCancellable = initiator is CancelInitiator.Operator && original != null
        if (authorization == null || original == null || !(merchantCancellable || operatorCancellable)) {
            return CancelStartResult.Rejected(CancelStartRejection.PAYMENT_NOT_CANCELLABLE)
        }
        PaymentCancellation.requireOriginalRoute(authorization, providerRequest)

        if (initiator is CancelInitiator.Merchant && control.activeIncidentHolds.any { it.paymentId == payment.id }) {
            return CancelStartResult.Rejected(CancelStartRejection.ORDER_ON_HOLD)
        }
        if (balance?.hasInFlight() == true) return CancelStartResult.Rejected(CancelStartRejection.CANCEL_IN_PROGRESS)

        if (money.currency != original.money.currency) return CancelStartResult.Rejected(CancelStartRejection.CURRENCY_MISMATCH)
        val cancellable = balance?.cancellableMinor ?: original.money.amountMinor
        // review m-A: 반환 가능 금액 0이 FORCED에서만 나온 경우(완료 없음)는 전액 반환이 아니다.
        if (balance?.status == ReturnStatus.FULLY_CANCELLED) return CancelStartResult.Rejected(CancelStartRejection.ALREADY_FULLY_CANCELLED)
        if (money.amountMinor > cancellable) return CancelStartResult.Rejected(CancelStartRejection.AMOUNT_EXCEEDS_BALANCE)

        val untouched = balance == null || balance.reservations.none { it.state != ReturnReservationState.RELEASED }
        val scope = if (untouched && money == original.money) CancelScope.FULL else CancelScope.PARTIAL
        val cap = when (capability) {
            is CancelCapabilityResolution.Resolved -> capability.capability
            CancelCapabilityResolution.Unavailable -> return CancelStartResult.Rejected(CancelStartRejection.CAPABILITY_UNAVAILABLE)
        }
        if (scope == CancelScope.PARTIAL && !cap.supportsPartial(original.terms.plan, original.terms.cardType)) {
            return CancelStartResult.Rejected(CancelStartRejection.PARTIAL_CANCEL_NOT_SUPPORTED)
        }

        if (initiator is CancelInitiator.Operator) {
            initiator.approvalDefect()?.let { return CancelStartResult.Rejected(it) }
            val current = OperatorCancelTarget.of(payment.id, money, balance?.revision ?: 0L, initiator).fingerprint()
            if (current != initiator.approvedTargetFingerprint) return CancelStartResult.Rejected(CancelStartRejection.OPERATOR_TARGET_STALE)
        }

        val target = balance ?: ReturnBalance.open(authorization)
        val cancellation = PaymentCancellation.open(
            id = cancellationId,
            payment = payment,
            authorization = authorization,
            money = money,
            scope = scope,
            initiator = initiator,
            attemptId = attemptId,
            providerRequest = providerRequest,
            openedAt = at,
        )
        target.reserve(cancellation.id, money)
        CancelStartResult.Started(cancellation, target, balanceOpened = balance == null)
    }
}
