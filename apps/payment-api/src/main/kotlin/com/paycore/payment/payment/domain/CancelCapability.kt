package com.paycore.payment.payment.domain

/**
 * 원 경로 기관 프로파일의 취소 Capability(버전 있는 값, Demo — 범위 §1 "기관 지원 범위 내"(D-AUTH-001), D-CANCEL-004: PG 자체 취소 기한 없음,
 * 기관 기한 경과는 기관 거절로 FAILED). 부분취소 지원 여부를 결제 방식·카드 종류별로 답한다(일반 할부 부분취소도 이 값이 정한다 — PG 자체 금지 규칙 없음).
 */
data class CancelCapability(
    val version: String,
    val partialCancelLumpSum: Set<CardType>,
    val partialCancelInstallment: Set<CardType>,
) {
    init {
        require(version.isNotEmpty()) { "cancel capability version must not be empty" }
    }

    fun supportsPartial(plan: PaymentPlan, cardType: CardType): Boolean = when (plan) {
        PaymentPlan.LumpSum -> cardType in partialCancelLumpSum
        is PaymentPlan.Installment -> cardType in partialCancelInstallment
    }
}

/** 원 경로 프로파일 해석 결과. 없음·미지원 버전은 [Unavailable] — 취소 시작 fail-closed. */
sealed interface CancelCapabilityResolution {
    data class Resolved(val capability: CancelCapability) : CancelCapabilityResolution

    data object Unavailable : CancelCapabilityResolution
}
