package com.paycore.payment.payment.domain

import java.time.Instant
import java.time.LocalDate

enum class PaymentTransactionType { AUTHORIZATION, CANCEL }

/**
 * 확정 금융 사실(D-AUTH-003, 사용자 확정 2026-10-15): 기관이 확정한 금융 효과 1건 = PaymentTransaction 1건(append-only). 승인 성공 =
 * [AuthorizationTransaction], 취소·부분취소 = 각각 [CancelTransaction](CANCEL-CONTRACT-001 CC-03). 이벤트 1건 = 거래 1건, FINANCIAL 업무 키 = [id].
 * 늦은/상충 결과는 hold 해제 판정 전에는 거래를 만들지 않는다.
 */
sealed interface PaymentTransaction {
    val id: PaymentTransactionId
    val type: PaymentTransactionType
    val paymentId: PaymentId
    val authorizationId: AuthorizationId
    val money: Money
    val confirmedAt: Instant
}

/** 승인 성공 거래. PaymentAuthorized 이벤트 1건 = 이 거래 1건. */
data class AuthorizationTransaction(
    override val id: PaymentTransactionId,
    override val authorizationId: AuthorizationId,
    override val paymentId: PaymentId,
    val terms: ApprovedTerms,
    val sourceObservation: ObservationKey,
    override val confirmedAt: Instant,
) : PaymentTransaction {
    override val type: PaymentTransactionType
        get() = PaymentTransactionType.AUTHORIZATION

    override val money: Money
        get() = terms.money
}

/**
 * 취소 거래(전액·부분 각각 1건, D-AUTH-003). 원 AUTHORIZATION 거래([originalTransactionId])를 참조한다 — 수수료·VAT 반환은 원승인 Snapshot 기준
 * (K-7, D-CANCEL-008 계산은 SETTLEMENT). 정산 거래일 근거는 기관 취소 거래일([institutionTransactionDate])이며 PG 확정 시각([confirmedAt])과
 * 섞지 않는다(§5.3). PaymentCancelled 이벤트 1건 = 이 거래 1건.
 */
data class CancelTransaction(
    override val id: PaymentTransactionId,
    override val paymentId: PaymentId,
    override val authorizationId: AuthorizationId,
    val cancellationId: CancellationId,
    val originalTransactionId: PaymentTransactionId,
    val scope: CancelScope,
    val terms: CancelConfirmedTerms,
    val sourceObservation: CancelObservationKey,
    override val confirmedAt: Instant,
) : PaymentTransaction {
    init {
        require(id != originalTransactionId) { "a cancel transaction cannot be its own original transaction" }
    }

    override val type: PaymentTransactionType
        get() = PaymentTransactionType.CANCEL

    override val money: Money
        get() = terms.money

    val institutionTransactionDate: LocalDate
        get() = terms.institutionTransactionDate
}
