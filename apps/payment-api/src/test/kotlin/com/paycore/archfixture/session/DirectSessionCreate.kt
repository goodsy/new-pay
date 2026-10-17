package com.paycore.archfixture.session

import com.paycore.payment.payment.domain.AuthenticationBinding
import com.paycore.payment.payment.domain.AuthenticationCorrelation
import com.paycore.payment.payment.domain.AuthenticationSession
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.Payment
import java.time.Instant

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): `AuthenticationSessionStart` 밖에서 세션을 만든다(이전 세션 대체 생략 우회).
 * 대상 = AuthenticationSession.Companion.create.
 */
class DirectSessionCreate {
    fun bypass(id: AuthenticationSessionId, payment: Payment, binding: AuthenticationBinding, correlation: AuthenticationCorrelation): AuthenticationSession =
        AuthenticationSession.create(id, payment, binding, correlation, Instant.EPOCH.plusSeconds(60), Instant.EPOCH, emptyList())
}
