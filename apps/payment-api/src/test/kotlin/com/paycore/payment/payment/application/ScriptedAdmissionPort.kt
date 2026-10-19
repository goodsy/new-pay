package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AdmittedRoute
import com.paycore.payment.payment.application.required.AuthorizationAdmissionPort
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.LimitAmount
import com.paycore.payment.payment.domain.LimitPolicyResolution
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.PaymentMethod
import com.paycore.payment.payment.domain.TransactionLimitPolicy
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** [AuthorizationAdmissionPort.evaluate] 한 번의 호출 인자와 호출 시 TX 여부. */
data class AdmissionCall(
    val mid: MerchantAccountId,
    val site: MerchantSiteId,
    val currency: CurrencyCode,
    val acquirerCode: String,
    val evaluatedAt: Instant,
    val inTransaction: Boolean,
)

/**
 * **시험 전용** 승인 시작 허용 판정 Port 대역(운영 구현은 `EntitlementAuthorizationAdmissionAdapter` — 그 배선은 `AuthorizationAdmissionSpec`이 실DB로 시험한다).
 * 기본 응답은 Demo Admitted: Route `SIM-VAN-01`(시험 Route 등록부와 같은 값), 기관 가맹점 `SIMMERCH0001`, 한도 정책 MID+통화 하나(건당 1,000,000·일 5,000,000 —
 * Demo 값, 운영값 아님). 시험은 자기 MID에만 [respondFor]로 응답을 바꾼다(공유 컨텍스트에서 섞이지 않게).
 */
class ScriptedAdmissionPort : AuthorizationAdmissionPort {
    val calls = ConcurrentLinkedQueue<AdmissionCall>()
    private val answers = ConcurrentHashMap<MerchantAccountId, (AdmissionCall) -> AdmissionResult>()

    override fun evaluate(
        authenticatedMid: MerchantAccountId,
        merchantSiteId: MerchantSiteId,
        currency: CurrencyCode,
        paymentMethod: PaymentMethod,
        acquirerCode: String,
        evaluatedAt: Instant,
    ): AdmissionResult {
        val call = AdmissionCall(authenticatedMid, merchantSiteId, currency, acquirerCode, evaluatedAt, TransactionSynchronizationManager.isActualTransactionActive())
        calls += call
        return answers[authenticatedMid]?.invoke(call) ?: admitted(call)
    }

    fun respondFor(mid: MerchantAccountId, answer: (AdmissionCall) -> AdmissionResult) {
        answers[mid] = answer
    }

    fun callsFor(mid: MerchantAccountId): List<AdmissionCall> = calls.filter { it.mid == mid }

    companion object {
        const val ROUTE_REF = "SIM-VAN-01"
        const val INSTITUTION_MERCHANT_REF = "SIMMERCH0001"

        fun policy(call: AdmissionCall, perTransaction: Long = 1_000_000, daily: Long = 5_000_000) =
            TransactionLimitPolicy("tlp_demo_${call.currency.value}", call.mid, call.currency, LimitAmount.Limited(perTransaction), LimitAmount.Limited(daily))

        fun admitted(
            call: AdmissionCall,
            limit: LimitPolicyResolution = LimitPolicyResolution.Resolved(policy(call)),
            routeRef: String = ROUTE_REF,
        ): AdmissionResult = AdmissionResult.Admitted(
            entitlementVersion = "es1:demo-merchant:1",
            policyAssignmentVersion = "pa1:fee=pas_demo/pv_demo;stl=pas_demo2/pv_demo2",
            route = AdmittedRoute("cra_demo", call.acquirerCode, routeRef, INSTITUTION_MERCHANT_REF),
            limitPolicy = limit,
            policyContentStatus = setOf("DEMO"),
            validUntil = call.evaluatedAt.plusSeconds(60),
            evaluatedAt = call.evaluatedAt,
        )
    }
}

@TestConfiguration(proxyBeanMethods = false)
class AdmissionTestConfig {
    @Bean
    @Primary
    fun scriptedAdmissionPort(): ScriptedAdmissionPort = ScriptedAdmissionPort()
}
