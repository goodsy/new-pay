package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.entitlement.application.provided.EligibilityDecision
import com.paycore.payment.entitlement.application.provided.EligibilityRefusalReason
import com.paycore.payment.entitlement.application.provided.EligibilityRequest
import com.paycore.payment.entitlement.application.provided.EligibilityUnavailableCause
import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationQuery
import com.paycore.payment.payment.application.provided.EligibilitySnapshotVersion
import com.paycore.payment.payment.application.provided.EligibilityUnavailableReason
import com.paycore.payment.payment.application.provided.NotEligibleReason
import com.paycore.payment.payment.application.required.EligibilityResult
import com.paycore.payment.payment.application.required.PaymentEligibilityPort
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.CurrencyUnit
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.PaymentMethod
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * [PaymentEligibilityPort]의 운영 구현(MASTER-SNAPSHOT-001 명세 §5.4, CP계약 §13). entitlement 모듈의 공개 use case([EntitlementEvaluationQuery],
 * `application.provided`만 — Modulith 공개 범위)를 같은 PAYMENT 로컬 TX 안에서 부르고 결과를 계약 모양으로 1:1 옮긴다. 판정 규칙은 entitlement
 * domain에만 있다. PAYMENT DB 로컬 투영만 읽는다(MASTER 직접 조회·원격 호출 없음, 캐시 없음).
 *
 * environment(TEST/LIVE)는 판정 입력이 아니다 — D-MS-001: TEST도 LIVE와 같은 조건(완화는 별도 결정 전까지 없음).
 */
@Component
class EntitlementEligibilityAdapter(private val query: EntitlementEvaluationQuery) : PaymentEligibilityPort {

    override fun evaluate(
        environment: Environment,
        authenticatedMid: MerchantAccountId,
        merchantSiteId: MerchantSiteId,
        currency: CurrencyCode,
        paymentMethod: PaymentMethod,
        evaluatedAt: Instant,
    ): EligibilityResult =
        when (val decision = query.evaluate(EligibilityRequest(authenticatedMid.value, merchantSiteId.value, paymentMethod.name, currency.value), evaluatedAt)) {
            is EligibilityDecision.Allowed -> EligibilityResult.Eligible(
                EligibilitySnapshotVersion(decision.snapshotVersion), decision.validUntil, CurrencyUnit(CurrencyCode(decision.currency), decision.minorUnit),
            )
            EligibilityDecision.NotFoundOrForbidden -> EligibilityResult.NotFoundOrForbidden
            is EligibilityDecision.Refused -> EligibilityResult.NotEligible(
                when (decision.reason) {
                    EligibilityRefusalReason.CURRENCY_NOT_ENABLED -> NotEligibleReason.CURRENCY_NOT_ENABLED
                    EligibilityRefusalReason.METHOD_NOT_ENABLED -> NotEligibleReason.METHOD_NOT_ENABLED
                    EligibilityRefusalReason.CONTRACT_NOT_EFFECTIVE -> NotEligibleReason.CONTRACT_NOT_EFFECTIVE
                    EligibilityRefusalReason.MERCHANT_SUSPENDED -> NotEligibleReason.MERCHANT_SUSPENDED
                },
            )
            is EligibilityDecision.Unavailable -> EligibilityResult.Unavailable(
                when (decision.reason) {
                    EligibilityUnavailableCause.SNAPSHOT_MISSING -> EligibilityUnavailableReason.SNAPSHOT_MISSING
                    EligibilityUnavailableCause.SNAPSHOT_EXPIRED -> EligibilityUnavailableReason.SNAPSHOT_EXPIRED
                    EligibilityUnavailableCause.VERSION_UNSUPPORTED -> EligibilityUnavailableReason.VERSION_UNSUPPORTED
                },
            )
        }
}
