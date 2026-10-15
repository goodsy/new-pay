package com.paycore.archfixture.startauth

import com.paycore.payment.payment.domain.AuthenticationSession
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import java.time.Instant

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 승인 시작 밖에서 인증 근거를 귀속한다(인증 근거 재사용 우회). 대상 = AuthenticationSession.bindTo. */
class DirectSessionBinding {
    fun bypass(session: AuthenticationSession, id: AuthorizationId, paymentId: PaymentId, money: Money) {
        session.bindTo(id, paymentId, money, Instant.EPOCH)
    }
}
