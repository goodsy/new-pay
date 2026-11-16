package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.HomeKpiItem
import com.paycore.payment.payment.application.provided.HomeKpiQuery
import com.paycore.payment.payment.application.provided.HomeKpiSnapshot
import com.paycore.payment.payment.application.required.HomeKpiReader
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.domain.HomeKpiWindow
import org.springframework.stereotype.Service

/**
 * [HomeKpiQuery](ADMIN-HOME-KPI-001 §3·§5). **TX를 열지 않는다** — asOf를 PAYMENT 시계로 먼저 정하고(§3.1) Reader의 한 RR 읽기 TX를 부른다. 빈 구간(KST 자정 정각)은 Reader를
 * 부르지 않고 빈 목록. 정렬(LIVE → TEST, currency 오름차순)은 여기서 한다(SQL ORDER BY 없음 → filesort 없음, §9). 같은 (environment, currency)가 두 번 오면 fail-closed(예외 → 503).
 * 실패 분류는 기존 관리 조회와 같다([OperatorDecisionViews.guard]).
 */
@Service
class HomeKpiQueryService(private val reader: HomeKpiReader, private val clock: PaymentClock) : HomeKpiQuery {

    override fun today(): HomeKpiSnapshot = OperatorDecisionViews.guard {
        val window = HomeKpiWindow.of(clock.now())
        val items = if (window.isEmpty) emptyList() else reader.read(window).sortedWith(ORDER)
        check(items.map { it.environment to it.currency }.toSet().size == items.size) { "duplicate home kpi row" }
        HomeKpiSnapshot(window.asOf, window.businessDate, HomeKpiWindow.ZONE.id, window.from, items)
    }

    private companion object {
        /** §3.4: LIVE 먼저(그 밖 환경은 뒤 — 현재 TEST뿐), 같은 환경은 통화 오름차순. */
        val ORDER: Comparator<HomeKpiItem> = compareBy<HomeKpiItem>({ if (it.environment == "LIVE") 0 else 1 }, { it.environment }, { it.currency })
    }
}
