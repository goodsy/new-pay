package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.CardAuthenticationPort
import com.paycore.payment.payment.application.required.CardAuthenticationRegistration
import com.paycore.payment.payment.application.required.RegistrationResult
import com.paycore.payment.payment.domain.AuthenticatedDetails
import com.paycore.payment.payment.domain.ProviderAuthenticationResult
import com.paycore.payment.payment.domain.ProviderBindingEcho
import com.paycore.payment.payment.domain.ProviderOutcome
import com.paycore.payment.payment.domain.ProviderResultLookup
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * **시험 전용** 카드 인증기관 Port 대역(운영 구현은 Simulator HTTP adapter — 계약은 `SimulatorCardAuthenticationAdapterSpec`·golden). 등록을 기록하고 기본으로 성공(거래 참조
 * `sat_`·고객 URL = 설정 고객 origin), 조회는 시험이 정한 결과를 돌려준다. 호출 순간 이 스레드에 열린 DB TX가 있었는지 기록한다(C10 — 외부 호출은 TX 밖).
 * 시험은 자기 state·결과 참조에 대해서만 응답을 정한다(공유 컨텍스트에서 섞이지 않게).
 */
class ScriptedCardAuthenticationPort : CardAuthenticationPort {
    data class Call(val operation: String, val inTransaction: Boolean)

    val registrations = ConcurrentLinkedQueue<CardAuthenticationRegistration>()
    val calls = ConcurrentLinkedQueue<Call>()
    private val registerAnswers = ConcurrentHashMap<String, (CardAuthenticationRegistration) -> RegistrationResult>()
    private val lookups = ConcurrentHashMap<String, () -> ProviderResultLookup>()
    @Volatile var beforeLookup: (String) -> Unit = {}

    override fun register(request: CardAuthenticationRegistration): RegistrationResult {
        calls += Call("register", TransactionSynchronizationManager.isActualTransactionActive())
        registrations += request
        val answer = registerAnswers[request.binding.orderKey.merchantAccountId.value]
        return answer?.invoke(request) ?: registered()
    }

    override fun lookup(resultRef: String): ProviderResultLookup {
        calls += Call("lookup", TransactionSynchronizationManager.isActualTransactionActive())
        beforeLookup(resultRef)
        return lookups[resultRef]?.invoke() ?: ProviderResultLookup.Unverifiable(com.paycore.payment.payment.domain.LookupFailure.NOT_FOUND)
    }

    /** 이 MID의 등록 응답을 바꾼다. */
    fun registerFor(mid: String, answer: (CardAuthenticationRegistration) -> RegistrationResult) {
        registerAnswers[mid] = answer
    }

    fun lookupFor(resultRef: String, answer: () -> ProviderResultLookup) {
        lookups[resultRef] = answer
    }

    /** [registration]에 대한 기관 결과(바인딩 echo = 등록 바인딩, state echo = 등록 state). [mutate]로 Invalid 의도 결과를 만든다. */
    fun authenticated(
        registration: CardAuthenticationRegistration,
        transactionRef: String,
        validFor: Duration = Duration.ofMinutes(10),
        cardType: String = "CREDIT",
        outcome: ProviderOutcome = ProviderOutcome.AUTHENTICATED,
        mutate: (ProviderAuthenticationResult) -> ProviderAuthenticationResult = { it },
    ): ProviderAuthenticationResult {
        val b = registration.binding
        val now = Instant.now()
        val details = if (outcome == ProviderOutcome.AUTHENTICATED) AuthenticatedDetails(cardType, b.cardCompany, "SIMACQ-A", "SIMAUTH-1", "sim-ev-" + UUID.randomUUID(), now) else null
        return mutate(
            ProviderAuthenticationResult(
                providerTransactionRef = transactionRef,
                outcome = outcome,
                stateDigest = SecretDigest.of(SecretKind.AUTHENTICATION_STATE, registration.state),
                binding = ProviderBindingEcho(b.paymentId, b.orderKey.environment, b.orderKey.merchantAccountId, b.money, b.plan, b.cardCompany),
                authenticated = details,
                validUntil = now.plus(validFor),
            ),
        )
    }

    companion object {
        const val CUSTOMER_ORIGIN = "https://sim.example.test"

        fun newTransactionRef(): String = "sat_" + UUID.randomUUID().toString().replace("-", "").take(22)

        fun newResultRef(): String = "srr_" + UUID.randomUUID().toString().replace("-", "").take(22)

        fun registered(transactionRef: String = newTransactionRef()) = RegistrationResult.Registered(transactionRef, "$CUSTOMER_ORIGIN/sim/authenticate/$transactionRef")
    }
}

@TestConfiguration(proxyBeanMethods = false)
class CardAuthenticationTestConfig {
    @Bean
    @Primary
    fun scriptedCardAuthenticationPort(): ScriptedCardAuthenticationPort = ScriptedCardAuthenticationPort()
}
