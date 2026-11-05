package com.paycore.financial.paymentfact.domain

/**
 * 결제 사실 투영 판정(FIN-FACT-RELAY-001 §4.3). 같은 Payment의 이미 반영한 사실([PaymentFactHistory] — 결제별 직렬화 잠금 아래에서 읽은 값)과 새 사실로
 * **보류 사유**를 정한다. 사실 자체는 보류여도 잃지 않고 투영한다(append-only — 확정 사실은 PAYMENT가 이미 확정했다). 보류는 "자동 후속 처리(매입·상계)의
 * 입력으로 바로 쓰지 말라"는 표시이고, 해제 규칙은 매입 작업이 정한다(D-ACQ-002 — 이 규칙은 해제하지 않는다).
 *
 * - [HoldReason.ORIGINAL_UNKNOWN]: 원승인 투영이 없는 취소(D-ACQ-002 "원거래를 모르는 취소는 Hold" — 사용자 결정).
 * - 나머지는 fail-closed 정합성 보류(Implementer 해석 — 명세 §2 I-5): 신원 불일치, 같은 Payment의 두 번째 다른 승인 거래, 원승인과 금액·통화·승인 ID가 맞지 않는 취소,
 *   같은 원승인·같은 반환 순번의 다른 취소, 받은 취소 합계가 원승인 금액을 넘음.
 */
object PaymentFactProjectionRule {

    fun decide(history: PaymentFactHistory, fact: PaymentFact): ProjectionDecision {
        val holds = linkedSetOf<HoldReason>()
        history.owner?.let { if (!it.sameOwner(fact.identity)) holds += HoldReason.IDENTITY_MISMATCH }
        when (fact) {
            is AuthorizedPaymentFact -> {
                if (history.authorizations.any { it.transactionId != fact.transactionId }) holds += HoldReason.AUTHORIZATION_CONFLICT
            }
            is CancelledPaymentFact -> holds += cancellationHolds(history, fact)
        }
        return ProjectionDecision(holds)
    }

    private fun cancellationHolds(history: PaymentFactHistory, cancel: CancelledPaymentFact): Set<HoldReason> {
        val original = history.authorizations.firstOrNull { it.transactionId == cancel.originalTransactionId }
            ?: return setOf(HoldReason.ORIGINAL_UNKNOWN)
        val holds = linkedSetOf<HoldReason>()
        val matches = original.authorizationId == cancel.authorizationId && original.identity.paymentId == cancel.identity.paymentId &&
            original.money.currency == cancel.money.currency && original.money.minor == cancel.authorizedAmountMinor &&
            original.providerTransactionId == cancel.originalProviderTransactionId
        if (!matches) holds += HoldReason.ORIGINAL_MISMATCH
        val siblings = history.cancellations.filter { it.originalTransactionId == cancel.originalTransactionId && it.transactionId != cancel.transactionId }
        if (siblings.any { it.returnSequence == cancel.returnSequence }) holds += HoldReason.RETURN_SEQUENCE_CONFLICT
        // 통화가 다르면 합산하지 않는다(ORIGINAL_MISMATCH로 이미 보류). 같은 통화의 받은 취소 합계만 원승인 금액과 비교한다.
        if (original.money.currency == cancel.money.currency) {
            val returned = siblings.filter { it.money.currency == original.money.currency }.sumOf { it.money.minor } + cancel.money.minor
            if (returned > original.money.minor) holds += HoldReason.RETURN_EXCEEDS_AUTHORIZED
        }
        return holds
    }
}

/** 판정 결과. 보류 사유가 없으면 APPLIED, 있으면 HELD(사실은 둘 다 투영한다). */
data class ProjectionDecision(val holds: Set<HoldReason>) {
    val outcome: PaymentFactInboxOutcome get() = if (holds.isEmpty()) PaymentFactInboxOutcome.APPLIED else PaymentFactInboxOutcome.HELD
}
