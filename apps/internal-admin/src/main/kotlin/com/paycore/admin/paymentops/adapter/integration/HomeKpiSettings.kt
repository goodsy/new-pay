package com.paycore.admin.paymentops.adapter.integration

import com.paycore.admin.paymentops.application.required.HomeKpiLimits
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 운영 홈 KPI 속도 제한(ADMIN-HOME-KPI-001 C-4 — `paycore.admin.payment-ops.home-kpi-per-minute`, **필수 — 기본값 없음**, 빠지면 기동 실패, Demo 30/분 — 운영값 아님, LIVE Gate).
 * 거래 조회 `lookup-per-minute`와 별개 창. 같은 접두사의 [PaymentOpsSettings]와 따로 둔 것은 구현 세부(기존 설정 생성자·시험을 바꾸지 않게). 화면은 polling하지 않는다.
 */
@ConfigurationProperties(prefix = "paycore.admin.payment-ops")
class HomeKpiSettings(homeKpiPerMinute: Int?) : HomeKpiLimits {
    override val homeKpiPerMinute: Int = requireNotNull(homeKpiPerMinute) { "paycore.admin.payment-ops.home-kpi-per-minute is required" }

    init {
        require(this.homeKpiPerMinute in 1..MAX_RATE) { "paycore.admin.payment-ops.home-kpi-per-minute 1..$MAX_RATE" }
    }

    private companion object {
        const val MAX_RATE = 10_000
    }
}
