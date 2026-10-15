package com.paycore.archfixture.startauth

import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.AuthorizationAdmission
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.LimitUsageKey
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.ProviderRequestRef
import java.time.Instant

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): AuthorizationStart 밖에서 승인 operation(Attempt PREPARED)을 만든다 — Payment AUTHORIZING·
 * 세션 귀속·한도 점유 없이 Attempt가 생긴다. `controlStartAuthorizationOnlyThroughAuthorizationStart`가 PaymentAuthorization.Companion.open을 잡아야 한다.
 */
class DirectAuthorizationOpen {
    fun bypass(
        id: AuthorizationId, payment: Payment, intent: OrderIntent, sessionId: AuthenticationSessionId, admission: AuthorizationAdmission,
        key: LimitUsageKey, attemptId: AttemptId, request: ProviderRequestRef,
    ): PaymentAuthorization =
        PaymentAuthorization.open(
            id, payment, intent, PaymentPlan.LumpSum, CardType.CREDIT, sessionId, admission, "v", "v", key, attemptId, request, Instant.EPOCH,
        )
}
