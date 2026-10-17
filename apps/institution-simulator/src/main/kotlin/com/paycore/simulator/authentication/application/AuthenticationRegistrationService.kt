package com.paycore.simulator.authentication.application

import com.paycore.simulator.authentication.application.provided.RegisterAuthenticationUseCase
import com.paycore.simulator.authentication.application.provided.Registration
import com.paycore.simulator.authentication.application.required.AuthenticationTransactionRepository
import com.paycore.simulator.authentication.application.required.DuplicateRegistration
import com.paycore.simulator.authentication.application.required.SimulatorClients
import com.paycore.simulator.authentication.application.required.SimulatorClock
import com.paycore.simulator.authentication.application.required.SimulatorIdGenerator
import com.paycore.simulator.authentication.application.required.SimulatorTimings
import com.paycore.simulator.authentication.domain.AuthenticationTransaction
import com.paycore.simulator.authentication.domain.RegistrationRequest
import com.paycore.simulator.authentication.domain.RejectionReason
import com.paycore.simulator.authentication.domain.SimulatorRejection
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 인증 등록(계약 v1 §13.4). 반환 URL은 그 client의 결제창 origin + 고정 경로 문법(`/checkout/s/{handle 22}/authentication/return`, query·fragment 없음)일
 * 때만 받는다 — Simulator가 open redirect가 되지 않는다(§13.3). (client, state) 멱등: 같은 내용이면 같은 거래(200), 다르면 409. 동시 같은 state 등록은 UNIQUE가
 * 직렬화하고 진 쪽은 새 TX에서 한 번 다시 판정한다.
 */
@Service
class AuthenticationRegistrationService(
    transactionManager: PlatformTransactionManager,
    private val transactions: AuthenticationTransactionRepository,
    private val clients: SimulatorClients,
    private val ids: SimulatorIdGenerator,
    private val clock: SimulatorClock,
    private val timings: SimulatorTimings,
) : RegisterAuthenticationUseCase {

    private val tx = TransactionTemplate(transactionManager)

    override fun register(clientId: String, request: RegistrationRequest): Registration {
        val client = clients.find(clientId) ?: throw SimulatorRejection(RejectionReason.NOT_FOUND)
        if (!returnUrlPattern(client.returnOrigin).matches(request.returnUrl)) throw SimulatorRejection(RejectionReason.RETURN_URL_NOT_ALLOWED)
        return try {
            checkNotNull(tx.execute { registerInTransaction(clientId, request) })
        } catch (e: DuplicateRegistration) {
            checkNotNull(tx.execute { registerInTransaction(clientId, request) })
        }
    }

    private fun registerInTransaction(clientId: String, request: RegistrationRequest): Registration {
        transactions.findByClientAndState(clientId, request.state)?.let { existing ->
            if (!existing.sameRequest(request)) throw SimulatorRejection(RejectionReason.STATE_REUSED_WITH_DIFFERENT_REQUEST)
            return registration(existing, created = false)
        }
        val created = AuthenticationTransaction.register(ids.transactionId(), clientId, request, clock.now(), timings.transactionTtl)
        transactions.insert(created)
        return registration(created, created = true)
    }

    private fun registration(t: AuthenticationTransaction, created: Boolean) =
        Registration(t.id, "${timings.customerBaseUrl}/sim/authenticate/${t.id.value}", t.expiresAt, created)

    private fun returnUrlPattern(origin: String) = Regex("^" + Regex.escape(origin) + "/checkout/s/[A-Za-z0-9_-]{22}/authentication/return$")
}
