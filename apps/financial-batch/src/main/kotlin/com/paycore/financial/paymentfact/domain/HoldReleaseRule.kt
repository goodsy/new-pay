package com.paycore.financial.paymentfact.domain

/**
 * ORIGINAL_UNKNOWN 보류의 자동 해제 판정(D-FFR-003 — 사용자 결정 2026-11-05, ACQUIRING-001 §12).
 *
 * 원승인보다 먼저 도착해 [HoldReason.ORIGINAL_UNKNOWN]으로 보류된 취소는, 원승인이 도착했을 때 **원승인을 처음부터 알았다면 투영 규칙이 아무 보류도 만들지 않았을**
 * 조건(신원·승인 ID·Payment·통화·승인액·기관 원거래번호 일치, 같은 원승인 반환 순번 유일, 누적 취소 ≤ 승인액 — Implementer 해석 I-13)을 만족하면 RELEASED,
 * 아니면 REVIEW_REQUIRED(운영자 검토)다. 원승인 자체가 보류면(정합성 보류) 딸린 취소도 자동 해제하지 않는다([HoldReleaseMismatch.ORIGINAL_HELD]).
 *
 * 판정은 [PaymentFactProjectionRule]을 원승인이 있는 이력으로 다시 적용해 같은 검사를 쓴다(검사 중복 구현 없음). 보류 원본 행은 바꾸지 않는다 — 결과는
 * append-only 해제 기록이다.
 */
object HoldReleaseRule {

    /**
     * @param owner 결제별 직렬화 행의 처음 신원(같은 Payment 사실의 비교 기준).
     * @param authorization 도착한 원승인.
     * @param authorizationHeld 원승인 자신에게 보류가 있는가.
     * @param cancellations 이 Payment의 모든 취소 투영(보류 포함 — 합계·순번 검사에 쓴다).
     * @param heldCancellationTransactionIds 해제 기록이 아직 없는 ORIGINAL_UNKNOWN 보류의 취소 거래 ID(이 원승인을 가리키는 것만).
     */
    fun decide(
        owner: PaymentFactIdentity?,
        authorization: AuthorizedPaymentFact,
        authorizationHeld: Boolean,
        cancellations: List<CancelledPaymentFact>,
        heldCancellationTransactionIds: Collection<String>,
    ): List<HoldReleaseDecision> {
        val byId = cancellations.associateBy { it.transactionId }
        return heldCancellationTransactionIds.sorted().map { id ->
            val cancel = requireNotNull(byId[id]) { "a held cancellation must be in the payment's cancellation history" }
            require(cancel.originalTransactionId == authorization.transactionId) { "the held cancellation must refer to this authorization" }
            val history = PaymentFactHistory(owner, listOf(authorization), cancellations.filter { it.transactionId != id })
            val mismatches = PaymentFactProjectionRule.decide(history, cancel).holds.map(::toMismatch).toMutableSet()
            if (authorizationHeld) mismatches += HoldReleaseMismatch.ORIGINAL_HELD
            HoldReleaseDecision(id, mismatches)
        }
    }

    private fun toMismatch(reason: HoldReason): HoldReleaseMismatch = when (reason) {
        HoldReason.ORIGINAL_MISMATCH -> HoldReleaseMismatch.ORIGINAL_MISMATCH
        HoldReason.RETURN_SEQUENCE_CONFLICT -> HoldReleaseMismatch.RETURN_SEQUENCE_CONFLICT
        HoldReason.RETURN_EXCEEDS_AUTHORIZED -> HoldReleaseMismatch.RETURN_EXCEEDS_AUTHORIZED
        HoldReason.IDENTITY_MISMATCH -> HoldReleaseMismatch.IDENTITY_MISMATCH
        // 원승인을 이력에 넣고 판정하므로 나올 수 없다. 나오면 규칙 결함이다(해제하지 않도록 실패).
        HoldReason.ORIGINAL_UNKNOWN, HoldReason.AUTHORIZATION_CONFLICT -> error("unexpected hold reason $reason when the original is known")
    }
}

/** 보류 해제 판정 하나. 불일치가 없으면 RELEASED, 있으면 REVIEW_REQUIRED(불일치 목록과 함께 기록). */
data class HoldReleaseDecision(val cancellationTransactionId: String, val mismatches: Set<HoldReleaseMismatch>) {
    val outcome: HoldReleaseOutcome get() = if (mismatches.isEmpty()) HoldReleaseOutcome.RELEASED else HoldReleaseOutcome.REVIEW_REQUIRED
}

/** DB CHECK `ck_payment_fact_hold_release_decision`과 같은 집합. */
enum class HoldReleaseOutcome { RELEASED, REVIEW_REQUIRED }

/** 자동 해제를 막는 불일치(DB에는 이름을 쉼표로 이어 기록 — 정렬 순서 고정). */
enum class HoldReleaseMismatch { ORIGINAL_HELD, IDENTITY_MISMATCH, ORIGINAL_MISMATCH, RETURN_SEQUENCE_CONFLICT, RETURN_EXCEEDS_AUTHORIZED }

/** 판정을 내린 경로: Inbox(원승인 수신 TX 안) 또는 SWEEP(기능 도입 전 보류의 일괄 처리 — 같은 규칙). DB CHECK와 같은 집합. */
enum class HoldReleaseTrigger { INBOX, SWEEP }
