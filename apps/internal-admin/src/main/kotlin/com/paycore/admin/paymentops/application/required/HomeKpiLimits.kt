package com.paycore.admin.paymentops.application.required

/** 운영 홈 KPI 속도 제한 값 Port(ADMIN-HOME-KPI-001 C-4 — 구현 `adapter.integration.HomeKpiSettings`, 키 `paycore.admin.payment-ops.home-kpi-per-minute` 필수, Demo 30 — 운영값 아님). */
interface HomeKpiLimits {
    val homeKpiPerMinute: Int
}
