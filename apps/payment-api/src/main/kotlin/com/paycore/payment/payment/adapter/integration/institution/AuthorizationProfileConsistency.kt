package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.domain.Environment
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component

/**
 * 기동 검사(AUTH-001 AU-31, rev 0.3 n-7, D-AUTH1-003 "결제창 Plan Demo 설정 일치"): 환경별 인증 프로파일의 결제창 Plan 정책과 같은 환경의 VAN Route Capability가 **통화별 허용
 * 할부 개월·할부 최소금액**에서 같아야 한다. 다르면 기동을 거부한다 — 고객이 결제창에서 고른 할부가 인증 뒤 승인 단계에서 처음 거절되는 경로를 막는다. Route가 지원하지 않는
 * 통화의 Plan 정책은 비교하지 않는다(confirm이 그 통화를 먼저 거절한다 — D-AUTH1-002). 체크카드 할부 거절은 설정이 아니라 고정 domain 규칙(`PaymentPlanRule`)이다.
 */
@Component
class AuthorizationProfileConsistency(
    private val authentication: AuthenticationSettings,
    private val routes: ConfiguredInstitutionRouteRegistry,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() = verify()

    fun verify() {
        Environment.entries.forEach { environment ->
            val policy = authentication.profileFor(environment)?.planPolicy ?: return@forEach
            routes.routes.filter { (_, r) -> r.environment == environment.name }.keys.forEach { routeRef ->
                val caps = checkNotNull(routes.resolve(routeRef)).capabilities
                if (policy.currency !in caps.supportedCurrencies) return@forEach
                check(caps.installmentMonths == policy.installmentMonths) {
                    "AU-31: route $routeRef installment months differ from the checkout plan policy ${policy.version} for ${policy.currency.value}"
                }
                check(caps.installmentMinimumAmount == policy.minimumInstallmentAmount?.amountMinor) {
                    "AU-31: route $routeRef installment minimum amount differs from the checkout plan policy ${policy.version} for ${policy.currency.value}"
                }
            }
        }
    }
}
