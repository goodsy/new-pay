package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.EligibilitySnapshotVersion
import com.paycore.payment.payment.application.provided.NotEligibleReason
import com.paycore.payment.payment.application.required.EligibilityResult
import com.paycore.payment.payment.application.required.PaymentEligibilityPort
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.CurrencyUnit
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentMethod
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** [PaymentEligibilityPort.evaluate] 한 번의 호출 인자. */
data class EligibilityCall(
    val environment: Environment,
    val authenticatedMid: MerchantAccountId,
    val merchantSiteId: MerchantSiteId,
    val currency: CurrencyCode,
    val paymentMethod: PaymentMethod,
    val evaluatedAt: Instant,
)

/**
 * **시험 전용** eligibility Port 대역(운영 구현 아님 — 운영 구현은 `payment.adapter.integration.EntitlementEligibilityAdapter`, MASTER-SNAPSHOT-001).
 * 모든 호출 인자를 기록하고, 기본으로 Demo Snapshot(`demo-snapshot-1`, 통화 단위 KRW 0·USD 2 — Demo fixture 값이며 운영 허용 통화
 * 목록이 아니다)에서 Eligible을 준다. 시험은 자기 MID(시험마다 고유)에 대해서만 [respondFor]로 응답을 바꾼다 — 같은 컨텍스트를 공유하는
 * 다른 시험·동시 요청과 섞이지 않는다.
 */
class RecordingEligibilityPort : PaymentEligibilityPort {
    private val calls = ConcurrentLinkedQueue<EligibilityCall>()
    private val answers = ConcurrentHashMap<Pair<Environment, MerchantAccountId>, (EligibilityCall) -> EligibilityResult>()

    override fun evaluate(
        environment: Environment,
        authenticatedMid: MerchantAccountId,
        merchantSiteId: MerchantSiteId,
        currency: CurrencyCode,
        paymentMethod: PaymentMethod,
        evaluatedAt: Instant,
    ): EligibilityResult {
        val call = EligibilityCall(environment, authenticatedMid, merchantSiteId, currency, paymentMethod, evaluatedAt)
        calls += call
        return (answers[environment to authenticatedMid] ?: ::demoAnswer)(call)
    }

    /** [order]의 (environment, MID)에 대한 응답을 바꾼다. */
    fun respondFor(order: OrderKey, answer: (EligibilityCall) -> EligibilityResult) {
        answers[order.environment to order.merchantAccountId] = answer
    }

    /** [order]의 (environment, MID)로 들어온 호출(순서대로). */
    fun callsFor(order: OrderKey): List<EligibilityCall> =
        calls.filter { it.environment == order.environment && it.authenticatedMid == order.merchantAccountId }

    companion object {
        val DEMO_SNAPSHOT = EligibilitySnapshotVersion("demo-snapshot-1")

        /** Demo 기준정보 통화 단위(KRW 0, USD 2). 운영값 아님. */
        private val DEMO_UNITS = listOf(CurrencyUnit(CurrencyCode("KRW"), 0), CurrencyUnit(CurrencyCode("USD"), 2)).associateBy { it.code }

        /** Demo Snapshot 유효 기간(시험 값). */
        private val DEMO_VALIDITY: Duration = Duration.ofMinutes(5)

        fun eligible(call: EligibilityCall, version: EligibilitySnapshotVersion = DEMO_SNAPSHOT): EligibilityResult =
            DEMO_UNITS[call.currency]?.let { EligibilityResult.Eligible(version, call.evaluatedAt.plus(DEMO_VALIDITY), it) }
                ?: EligibilityResult.NotEligible(NotEligibleReason.CURRENCY_NOT_ENABLED)

        private fun demoAnswer(call: EligibilityCall): EligibilityResult = eligible(call)
    }
}

/**
 * 시험 컨텍스트에 [RecordingEligibilityPort]를 등록한다. MASTER-SNAPSHOT-001부터 운영 main에 Port 구현(EntitlementEligibilityAdapter)이 있으므로
 * 이 대역은 `@Primary`로 그것을 대신한다(CreatePayment 업무 시험이 권리 투영 준비 없이 응답을 정할 수 있게). 운영 구현으로 기동하는 smoke는
 * 이 구성을 쓰지 않는다(PaymentApiContextSmokeSpec·EntitlementDbTest — L-11).
 */
@TestConfiguration(proxyBeanMethods = false)
class EligibilityTestConfig {
    @Bean
    @Primary
    fun recordingEligibilityPort(): RecordingEligibilityPort = RecordingEligibilityPort()
}
