package com.paycore.admin.financialops.application.required

/**
 * 조회 속도 제한 값 Port(ACQ-VIEW-001 §4.6 — 구현 `adapter.integration.FinancialOpsSettings`, **Demo 값, 운영값 아님** — LIVE Gate). actor별 분 단위 고정 창(인스턴스 메모리),
 * 목록·상세·레코드 쪽 공용.
 */
interface FinancialOpsLimits {
    val lookupPerMinute: Int
}
