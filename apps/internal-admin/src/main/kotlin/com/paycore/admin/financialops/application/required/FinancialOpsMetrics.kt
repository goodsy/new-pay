package com.paycore.admin.financialops.application.required

/** 매입 현황 조회 지표 Port(구현 Micrometer `paycore.admin.financial_ops.rate_limited`). label 없음(값·actor 없음). */
interface FinancialOpsMetrics {
    fun rateLimited()
}
