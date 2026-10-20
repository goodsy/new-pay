package com.paycore.simulator.van.domain

/**
 * 가상 카드별 가상 VAN 취소 동작(CANCEL-001 §6.4 Demo 표 — 결정 가능, seed 없음, 운영값 아님). 카드는 원 승인 원장 행의 카드 참조다(CN-25 — 승인은 정상, 취소만 다르다).
 * 전송 동작(늦은 응답·응답 없이 끊기·분할·MAC 오류)은 새 결정이 기록된 첫 응답에만 적용하고, 같은 요청 ID 재전송에는 기록된 결정을 정상 응답한다.
 */
enum class VanCancelBehaviour {
    NORMAL,
    DECLINE,
    LATE_RESPONSE,
    DROP_AFTER,
    DROP_BEFORE,
    PARTIAL,
    BAD_MAC,
    FRAGMENTED,
    INQUIRY_UNAVAILABLE_ONCE,
    ;

    companion object {
        private val BY_CARD = mapOf(
            "VC-CXL-DECLINE" to DECLINE,
            "VC-CXL-LATE-RESPONSE" to LATE_RESPONSE,
            "VC-CXL-DROP-AFTER" to DROP_AFTER,
            "VC-CXL-DROP-BEFORE" to DROP_BEFORE,
            "VC-CXL-PARTIAL" to PARTIAL,
            "VC-CXL-BAD-MAC" to BAD_MAC,
            "VC-CXL-FRAGMENTED" to FRAGMENTED,
            "VC-CXL-INQUIRY-UNAVAILABLE-ONCE" to INQUIRY_UNAVAILABLE_ONCE,
        )

        /** 카드 참조의 취소 동작(카탈로그에 없는 카드 = 정상 취소). */
        fun ofCard(cardRef: String?): VanCancelBehaviour = cardRef?.let { BY_CARD[it] } ?: NORMAL

        val cardRefs: Set<String> = BY_CARD.keys
    }
}
