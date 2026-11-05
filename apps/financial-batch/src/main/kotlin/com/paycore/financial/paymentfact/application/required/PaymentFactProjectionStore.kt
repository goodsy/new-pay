package com.paycore.financial.paymentfact.application.required

import com.paycore.financial.paymentfact.domain.AuthorizedPaymentFact
import com.paycore.financial.paymentfact.domain.CancelledPaymentFact
import com.paycore.financial.paymentfact.domain.HoldReason
import com.paycore.financial.paymentfact.domain.PaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFactHistory
import com.paycore.financial.paymentfact.domain.PaymentFactIdentity
import java.time.Instant

/**
 * PAYMENT 결제 사실 투영 저장 Port(CONVENTIONS §14 Store ② — 원천은 PAYMENT, FINANCIAL은 투영만 가진다). 호출자 TX 필수.
 *
 * 한 사실 TX: [lockStream](결제별 직렬화 — 없으면 첫 사실 신원으로 만든 뒤 `FOR UPDATE`) → [history] → (Inbox 기록) → [insertAuthorization]/[insertCancellation]
 * → [insertHolds] → [recordFact]. 투영·보류 행은 append-only(INSERT만, UPDATE·DELETE 경로 없음).
 */
interface PaymentFactProjectionStore {
    /** 결제별 직렬화 행을 잠근다. 반환 = 그 행에 기록된 처음 신원(이번이 첫 사실이면 null). */
    fun lockStream(identity: PaymentFactIdentity, at: Instant): PaymentFactIdentity?

    /** 잠금 아래에서 그 Payment에 이미 투영한 사실(보류 포함). */
    fun history(paymentId: String, owner: PaymentFactIdentity?): PaymentFactHistory

    fun insertAuthorization(fact: AuthorizedPaymentFact, sourceEventId: String, receivedAt: Instant)

    /** [originalKnownAtReceipt] = 수신 시점 원승인 투영 존재 여부(이후 바뀌지 않는다). */
    fun insertCancellation(fact: CancelledPaymentFact, originalKnownAtReceipt: Boolean, sourceEventId: String, receivedAt: Instant)

    fun insertHolds(fact: PaymentFact, reasons: Set<HoldReason>, sourceEventId: String, openedAt: Instant)

    /** 직렬화 행의 반영 건수 +1·갱신 시각. */
    fun recordFact(paymentId: String, at: Instant)
}
