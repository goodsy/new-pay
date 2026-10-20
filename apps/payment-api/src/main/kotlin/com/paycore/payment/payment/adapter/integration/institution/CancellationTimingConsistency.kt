package com.paycore.payment.payment.adapter.integration.institution

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component

/**
 * 취소 시간 설정 기동 검사(CANCEL-001 §6.6·CN-29 — S4 편차 D-S4-2를 S7에서 완성, 승인 `AuthorizationTimingConsistency`와 같은 식). 취소 Capability가 있는 모든 Route(sim-van-2)에서:
 * - `connectTimeout × (connectRetryBudget + 1) < cancel lease − safetyMargin` — lease 만료 뒤 늦은 첫 송신 방지.
 * - `cancel.closableAfter ≥ cancel preparedDeadline + cancel lease` — 전송이 끝났을 수 있는 시간 뒤에만 무효과 종결을 요청한다.
 * - 회복 `maxAge < cancel.inquiryRetention` — 보존기간 안에서 이관한다(보존기간 밖 "없음"은 기관이 확인해 줄 수 없다).
 * 어긋나면 기동 실패(운영 기본값 없음).
 */
@Component
class CancellationTimingConsistency(
    private val timings: CancellationTimingSettings,
    private val routes: ConfiguredInstitutionRouteRegistry,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() = verify()

    fun verify() {
        val window = timings.dispatchLease.minus(timings.safetyMargin)
        routes.routes.keys.forEach { routeRef ->
            val cancel = routes.resolve(routeRef)?.capabilities?.cancel ?: return@forEach
            val r = checkNotNull(routes.connection(routeRef))
            check(r.connectTimeout.multipliedBy((r.connectRetryBudget + 1).toLong()) < window) {
                "CANCEL-001: route $routeRef connect budget does not fit in the cancel dispatch lease − safety margin"
            }
            check(cancel.closableAfter >= timings.preparedDeadline.plus(timings.dispatchLease)) {
                "CANCEL-001: route $routeRef cancel closable-after must be at least cancel preparedDeadline + dispatchLease"
            }
            check(timings.maxAge < cancel.inquiryRetention) { "CANCEL-001: cancel recovery maxAge must be shorter than route $routeRef cancel inquiry retention" }
        }
    }
}
