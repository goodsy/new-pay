package com.paycore.simulator.authentication.application

import com.paycore.simulator.authentication.application.provided.CustomerAuthenticationUseCase
import com.paycore.simulator.authentication.application.provided.CustomerView
import com.paycore.simulator.authentication.application.required.AuthenticationTransactionRepository
import com.paycore.simulator.authentication.application.required.SimulatorClock
import com.paycore.simulator.authentication.application.required.SimulatorIdGenerator
import com.paycore.simulator.authentication.application.required.SimulatorTimings
import com.paycore.simulator.authentication.domain.AuthenticationTransaction
import com.paycore.simulator.authentication.domain.RejectionReason
import com.paycore.simulator.authentication.domain.SimulatorRejection
import com.paycore.simulator.authentication.domain.TransactionId
import com.paycore.simulator.authentication.domain.TransactionStatus
import com.paycore.simulator.authentication.domain.VirtualCard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 고객 인증 화면(§13.4). 고객이 가상 카드를 고르면 결과를 확정하고(한 번), PG 반환 URL에 1회용 결과 참조와 원래 state를 붙여 돌려준다. echo 변조는 서버 간 조회
 * 응답에서만 일어난다 — 브라우저가 가져가는 state는 원래 값이라 PG가 세션을 고를 수 있다.
 */
@Service
class CustomerAuthenticationService(
    private val transactions: AuthenticationTransactionRepository,
    private val ids: SimulatorIdGenerator,
    private val clock: SimulatorClock,
    private val timings: SimulatorTimings,
) : CustomerAuthenticationUseCase {

    @Transactional(readOnly = true)
    override fun view(id: TransactionId): CustomerView {
        val t = transactions.find(id) ?: throw SimulatorRejection(RejectionReason.NOT_FOUND)
        val open = t.status == TransactionStatus.REGISTERED && clock.now().isBefore(t.expiresAt)
        return CustomerView(t.id.value, t.request.amountMinor, t.request.currency, t.request.cardCompany, open, VirtualCard.entries.map { it.ref })
    }

    @Transactional
    override fun authenticate(id: TransactionId, cardRef: String): String {
        val card = VirtualCard.fromRef(cardRef) ?: throw SimulatorRejection(RejectionReason.UNKNOWN_CARD)
        val t = transactions.lock(id) ?: throw SimulatorRejection(RejectionReason.NOT_FOUND)
        t.complete(card, ids.resultRef(), ids.evidenceRef(), clock.now(), timings.resultValidity)
        transactions.update(t)
        return redirectOf(t)
    }

    @Transactional
    override fun cancel(id: TransactionId): String {
        val t = transactions.lock(id) ?: throw SimulatorRejection(RejectionReason.NOT_FOUND)
        t.cancel(ids.resultRef(), clock.now())
        transactions.update(t)
        return redirectOf(t)
    }

    private fun redirectOf(t: AuthenticationTransaction): String =
        "${t.request.returnUrl}?resultRef=${checkNotNull(t.resultRef).value}&state=${t.request.state}"
}
