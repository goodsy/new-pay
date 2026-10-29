package com.paycore.admin.paymentops.application.required

import java.time.Duration

/** 설정 값 Port(구현 = `adapter.integration.PaymentOpsSettings` — 기동 때 범위·시간 순서(L-6) 검사를 마친 값, Demo 값 — I-24). */
interface PaymentOpsTimings {
    val executionWindow: Duration
    val staleAfter: Duration
    val backoffInitial: Duration
    val backoffMax: Duration
    val lookupPerMinute: Int
    val deadlineSkew: Duration
    val recoveryBatchSize: Int
    val decisionStableDelay: Duration
    val reconciliationMaxPages: Int
    val reconciliationFullScanInterval: Duration
}
