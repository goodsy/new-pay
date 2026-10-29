package com.paycore.admin.paymentops.application.provided

/**
 * 화면용 PAYMENT 대상 조회(ADMIN-PAYMENT-OPS-001 §4.20·I-23 — 모든 조회 역할(VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN), 조회 감사 없음). `lookup` permit pool +
 * actor별 속도 제한(Demo 30회/분 — 초과 [LookupResult.RateLimited], 웹 429). VIEWER 조회 폭주가 승인 A 단계(`approval` pool)를 막지 못한다(L-8).
 */
interface PaymentTargetLookupUseCase {
    fun lookup(viewerActorId: String, paymentId: String): LookupResult
}

sealed interface LookupResult {
    data class Found(val view: PaymentTargetView) : LookupResult

    data object InvalidInput : LookupResult

    data object Forbidden : LookupResult

    data object NotFound : LookupResult

    data object RateLimited : LookupResult

    data class Unavailable(val reason: String) : LookupResult
}
