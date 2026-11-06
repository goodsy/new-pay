package com.paycore.financial.paymentfact.application.required

import com.paycore.financial.paymentfact.domain.AuthorizedPaymentFact
import com.paycore.financial.paymentfact.domain.CancelledPaymentFact
import com.paycore.financial.paymentfact.domain.HoldReason
import com.paycore.financial.paymentfact.domain.HoldReleaseDecision
import com.paycore.financial.paymentfact.domain.HoldReleaseTrigger
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

    // ── D-FFR-003 ORIGINAL_UNKNOWN 자동 해제(ACQUIRING-001 §12). 모두 결제별 직렬화 행 잠금 아래에서 부른다. ──

    /** 이 Payment에서 원승인 [originalTransactionId]를 가리키는 취소의 ORIGINAL_UNKNOWN 보류 중 해제 기록이 아직 없는 것(보류 id 순). */
    fun unreleasedOriginalUnknownHolds(paymentId: String, originalTransactionId: String): List<OpenHold>

    /** 거래 [transactionId]에 보류 행이 하나라도 있는가(원승인 자신의 정합성 보류 — 해제 경로 없음). */
    fun hasHolds(transactionId: String): Boolean

    /** 해제 판정 기록(append-only, 보류 하나당 한 행 — UNIQUE(hold_id)). 보류 원본 행은 바꾸지 않는다. */
    fun insertHoldReleases(
        paymentId: String,
        authorizationTransactionId: String,
        decisions: List<Pair<OpenHold, HoldReleaseDecision>>,
        trigger: HoldReleaseTrigger,
        at: Instant,
    )

    /** 일괄 처리(SWEEP) 대상: 해제 기록 없는 ORIGINAL_UNKNOWN 보류의 원승인이 이미 투영된 Payment(최대 [limit]개, payment_id 순). 잠금 없음. */
    fun paymentsWithReleasableHolds(limit: Int): List<String>

    /** 이미 있는 직렬화 행을 잠근다(없으면 null). 반환 = 처음 신원. */
    fun lockExistingStream(paymentId: String): PaymentFactIdentity?
}

/** 해제 기록이 없는 보류 하나(보류 행 id와 보류된 취소 거래 ID). */
data class OpenHold(val holdId: Long, val cancellationTransactionId: String)
