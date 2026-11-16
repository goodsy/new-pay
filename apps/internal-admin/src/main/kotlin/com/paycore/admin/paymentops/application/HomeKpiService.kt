package com.paycore.admin.paymentops.application

import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.paymentops.application.provided.HomeKpiResult
import com.paycore.admin.paymentops.application.provided.HomeKpiUseCase
import com.paycore.admin.paymentops.application.required.HomeKpiGateway
import com.paycore.admin.paymentops.application.required.HomeKpiLimits
import com.paycore.admin.paymentops.application.required.HomeKpiMetrics
import com.paycore.admin.paymentops.application.required.PaymentReadResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * [HomeKpiUseCase](ADMIN-HOME-KPI-001 §4.2·§6·§8). 판정 순서: 계정 현재 행 ACTIVE + VIEW 4역할(아니면 Forbidden — PAYMENT 미호출) → actor별 분당 창(`home-kpi-per-minute` —
 * 거래 조회 창과 별개 map, C-4) → PAYMENT 호출(DB TX 밖 — gateway가 TX 안 호출을 거부, 이 서비스는 TX를 열지 않는다) → 결과. **감사 없음**(§6 — 합계뿐).
 * 결과마다 지표 `home_kpi{result}` 하나. 로그에는 연산·결과 분류만(금액·환경·actor 없음).
 */
@Service
class HomeKpiService(
    private val sessions: AdminSessionQuery,
    private val gateway: HomeKpiGateway,
    private val limits: HomeKpiLimits,
    private val metrics: HomeKpiMetrics,
) : HomeKpiUseCase {

    private val windows = ConcurrentHashMap<String, LongArray>()

    override fun today(viewerActorId: String): HomeKpiResult {
        val result = when {
            sessions.current(viewerActorId)?.takeIf { it.isActive && it.roles.any { role -> role in VIEW_ROLES } } == null -> HomeKpiResult.Forbidden
            !admit(viewerActorId) -> HomeKpiResult.RateLimited
            else -> when (val r = gateway.homeKpis()) {
                is PaymentReadResult.Found -> HomeKpiResult.Found(r.document)
                PaymentReadResult.NotFound -> HomeKpiResult.Unavailable(UNEXPECTED_NOT_FOUND)
                is PaymentReadResult.Unavailable -> HomeKpiResult.Unavailable(r.reason)
            }
        }
        metrics.result(
            when (result) {
                is HomeKpiResult.Found -> "OK"
                HomeKpiResult.Forbidden -> "FORBIDDEN"
                HomeKpiResult.RateLimited -> "RATE_LIMITED"
                is HomeKpiResult.Unavailable -> "UNAVAILABLE"
            },
        )
        log.info("operation=home-kpi result={}", result.javaClass.simpleName)
        return result
    }

    /** 고정 창(분) 계수: [0] = 창 번호, [1] = 그 창의 호출 수(인스턴스 메모리 — 다중 인스턴스 합산은 LIVE Gate, L-3). */
    private fun admit(actorId: String): Boolean {
        val minute = System.currentTimeMillis() / MINUTE_MS
        val slot = windows.computeIfAbsent(actorId) { LongArray(2) }
        synchronized(slot) {
            if (slot[0] != minute) {
                slot[0] = minute
                slot[1] = 0
            }
            if (slot[1] >= limits.homeKpiPerMinute) return false
            slot[1]++
            return true
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(HomeKpiService::class.java)

        /** 조회 역할(C-2 — 거래 조회와 같은 VIEW 4역할). VIEWER는 이미 개별 거래 금액을 보므로 합계가 새 정보를 노출하지 않는다(§6). */
        val VIEW_ROLES = setOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN")
        const val MINUTE_MS = 60_000L
        const val UNEXPECTED_NOT_FOUND = "UNEXPECTED_NOT_FOUND"
    }
}
