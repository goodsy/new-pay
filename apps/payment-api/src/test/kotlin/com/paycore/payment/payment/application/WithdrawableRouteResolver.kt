package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.integration.institution.ConfiguredInstitutionRouteRegistry
import com.paycore.payment.payment.application.required.InstitutionRouteProfile
import com.paycore.payment.payment.application.required.InstitutionRouteResolver
import java.util.concurrent.ConcurrentHashMap

/**
 * 운영 [ConfiguredInstitutionRouteRegistry]를 감싼 Route 조회 Port(@Primary, 시험 전용 — [ObservedAuthorizationPort]와 같은 방식). [withdraw]한 routeRef는
 * 등록부에서 사라진 것처럼 null을 돌려준다 — TX1 뒤 재기동 설정 변경으로 Route가 빠진 경우(D-AUTH1-007, 최종 검토 m-1)를 재현한다. 시험은 끝에 [reset]한다.
 */
class WithdrawableRouteResolver(private val real: ConfiguredInstitutionRouteRegistry) : InstitutionRouteResolver {
    private val withdrawn = ConcurrentHashMap.newKeySet<String>()

    private val replaced = ConcurrentHashMap<String, (InstitutionRouteProfile) -> InstitutionRouteProfile>()

    override fun resolve(routeRef: String): InstitutionRouteProfile? =
        if (routeRef in withdrawn) null else real.resolve(routeRef)?.let { p -> replaced[routeRef]?.invoke(p) ?: p }

    /** CANCEL-001 m-4: 등록부의 Route 프로파일이 재기동 설정 변경으로 바뀐 경우(기관·환경 등)를 재현한다. */
    fun replace(routeRef: String, change: (InstitutionRouteProfile) -> InstitutionRouteProfile) {
        replaced[routeRef] = change
    }

    fun withdraw(routeRef: String) {
        withdrawn += routeRef
    }

    fun reset() {
        withdrawn.clear()
        replaced.clear()
    }
}
