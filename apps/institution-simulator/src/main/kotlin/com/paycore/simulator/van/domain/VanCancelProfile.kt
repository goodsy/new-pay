package com.paycore.simulator.van.domain

import java.time.Duration

/**
 * 가상 기관 취소 Capability(CANCEL-001 §6.3-4·§6.3-5·§6.5 — **Demo 설정, 운영값 아님, 운영 기본값 없음**). [confirmsNoEffect]가 있으면 받은 적 없는 취소 요청 ID를
 * [closableAfter] 이후 [inquiryRetention] 안에서 무효과 종결한다. [window]는 원 승인 기록 시각부터의 Demo 취소 기한(D-CANCEL-004 — PG는 사전 판정하지 않는다).
 * 부분취소 지원은 원 Plan별 cardType 집합이다(일시불 [partialLumpSumCardTypes], 할부 [partialInstallmentCardTypes]).
 */
data class VanCancelProfile(
    val confirmsNoEffect: Boolean,
    val closableAfter: Duration,
    val inquiryRetention: Duration,
    val window: Duration,
    val partialLumpSumCardTypes: Set<String>,
    val partialInstallmentCardTypes: Set<String>,
) {
    init {
        require(!closableAfter.isNegative && !window.isNegative && !window.isZero) { "cancel durations must be positive" }
        require(inquiryRetention > closableAfter) { "cancel inquiry retention must be longer than closableAfter" }
        require((partialLumpSumCardTypes + partialInstallmentCardTypes).all { CARD_TYPE.matches(it) }) { "card types must be codes" }
    }

    fun supportsPartial(plan: VanPlan, cardType: String): Boolean = when (plan.kind) {
        VanPlanKind.LUMP_SUM -> cardType in partialLumpSumCardTypes
        VanPlanKind.INSTALLMENT -> cardType in partialInstallmentCardTypes
    }

    private companion object {
        val CARD_TYPE = Regex("^[A-Z]{1,32}$")
    }
}
