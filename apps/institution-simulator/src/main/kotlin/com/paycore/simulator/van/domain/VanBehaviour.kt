package com.paycore.simulator.van.domain

/**
 * 가상 카드별 가상 VAN 동작(AUTH-001 §5.3 Demo 표 — 결정 가능, seed 없음, 운영값 아님). 카드 참조는 인증 모듈 카드 카탈로그와 같은 이름이다(AU-12). 전송 동작(늦은 응답·
 * 응답 없이 끊기·분할·중복·MAC 오류)은 새 결정이 기록된 첫 응답에만 적용하고, 같은 요청 ID 재전송에는 기록된 결정을 정상 응답한다.
 */
enum class VanBehaviour {
    NORMAL,
    DECLINE,
    LATE_RESPONSE,
    DROP_AFTER_APPROVE,
    DROP_BEFORE_RECORD,
    PENDING_THEN_APPROVE,
    DUPLICATE_RESPONSE,
    PARTIAL_APPROVAL,
    CARDTYPE_MISMATCH,
    INSTALLMENT_MONTHS_MISMATCH,
    FRAGMENTED,
    BAD_MAC,
    INQUIRY_UNAVAILABLE_ONCE,
    ;

    companion object {
        private val BY_CARD = mapOf(
            "VC-VAN-DECLINE" to DECLINE,
            "VC-VAN-LATE-RESPONSE" to LATE_RESPONSE,
            "VC-VAN-DROP-AFTER-APPROVE" to DROP_AFTER_APPROVE,
            "VC-VAN-DROP-BEFORE-RECORD" to DROP_BEFORE_RECORD,
            "VC-VAN-PENDING-THEN-APPROVE" to PENDING_THEN_APPROVE,
            "VC-VAN-DUPLICATE-RESPONSE" to DUPLICATE_RESPONSE,
            "VC-VAN-PARTIAL-APPROVAL" to PARTIAL_APPROVAL,
            "VC-VAN-CARDTYPE-MISMATCH" to CARDTYPE_MISMATCH,
            "VC-VAN-MONTHS-MISMATCH" to INSTALLMENT_MONTHS_MISMATCH,
            "VC-VAN-FRAGMENTED" to FRAGMENTED,
            "VC-VAN-BAD-MAC" to BAD_MAC,
            "VC-VAN-INQUIRY-UNAVAILABLE-ONCE" to INQUIRY_UNAVAILABLE_ONCE,
        )

        /** 카드 참조의 VAN 동작(카탈로그에 없는 카드 = 정상 승인). */
        fun ofCard(cardRef: String?): VanBehaviour = cardRef?.let { BY_CARD[it] } ?: NORMAL

        val cardRefs: Set<String> = BY_CARD.keys
    }
}
