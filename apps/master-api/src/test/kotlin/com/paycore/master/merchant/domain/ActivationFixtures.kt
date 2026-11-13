package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 결제수단 개통 Named Fixture(MERCHANT-OPS-002 §8.4 B3-1F — src/test 전용, Test Fixture Policy). 등록자가 업무 조건이므로 이름 있는 인자로 드러낸다:
 * `underReview(registeredBy = ApprovalEvidenceFixtures.APPROVER)` = "승인하려는 사람이 등록한 개통"(D-MOPS-009 ③ 무효 의도). 공식 경로(register)로만 만든다.
 */
object ActivationFixtures {
    /** 최초 Default 발급까지 끝난 구조 + [registeredBy]가 등록한 CARD 개통(UNDER_REVIEW). */
    fun underReview(registeredBy: ActorRef, at: Instant = EntitlementFixture.t0): EntitlementFixture.Registered {
        val issued = MasterFixture.issued(at)
        val (activation, _) = PaymentMethodActivation.register(MasterFixture.activationId(), issued.issuance.account, PaymentMethod.CARD, at, registeredBy)
        return EntitlementFixture.Registered(issued, activation)
    }
}
