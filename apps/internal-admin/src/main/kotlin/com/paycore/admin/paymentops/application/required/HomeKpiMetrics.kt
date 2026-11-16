package com.paycore.admin.paymentops.application.required

/** 운영 홈 KPI 지표 Port(ADMIN-HOME-KPI-001 §8 — 구현 Micrometer counter `paycore.admin.ops.home_kpi{result}`, result ∈ OK·FORBIDDEN·RATE_LIMITED·UNAVAILABLE). ID·금액·환경 label 없음. */
interface HomeKpiMetrics {
    fun result(result: String)
}
