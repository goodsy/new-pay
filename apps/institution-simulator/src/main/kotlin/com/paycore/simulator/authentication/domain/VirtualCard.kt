package com.paycore.simulator.authentication.domain

/** 결과 조회의 Simulator 동작(Recoverability 시나리오). */
enum class LookupBehaviour { NORMAL, DELAY, SERVER_ERROR }

/** 가상 카드 시나리오(결과·echo 변조·조회 동작). */
enum class CardScenario { AUTHENTICATE, FAIL, EXPIRED_RESULT, WRONG_AMOUNT, WRONG_MID, STATE_MISMATCH, LOOKUP_TIMEOUT_ONCE, LOOKUP_5XX_ONCE }

/**
 * 가상 카드 카탈로그(AUTHN-001 §13.2 — **Demo 코드 상수, 운영값 아님**). 카드번호가 아니라 참조 이름이다. issuer·acquirer·authenticationProvider는 서로 다른
 * 이름이다(VC-SPLIT-OK는 셋 모두 기본과 다른 경로). 세 코드는 기관 식별 코드 공통 형식 `^[A-Z0-9]{1,32}$`(D-INST-CODE-001 — INST-CODE-001 §3 매핑). cardType은 계약의 열린 토큰(PREPAID는 PayCore 미지원 — PG R10이 거절).
 */
enum class VirtualCard(
    val ref: String,
    val issuerCode: String,
    val acquirerCode: String,
    val providerCode: String,
    val cardType: String,
    val scenario: CardScenario,
) {
    VC_CREDIT_OK("VC-CREDIT-OK", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_CHECK_OK("VC-CHECK-OK", "SIMISSA", "SIMACQA", "SIMAUTH1", "CHECK", CardScenario.AUTHENTICATE),
    VC_SPLIT_OK("VC-SPLIT-OK", "SIMISSB", "SIMACQA", "SIMAUTH2", "CREDIT", CardScenario.AUTHENTICATE),
    VC_AUTH_FAIL("VC-AUTH-FAIL", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.FAIL),
    VC_EXPIRED("VC-EXPIRED", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.EXPIRED_RESULT),
    VC_WRONG_AMOUNT("VC-WRONG-AMOUNT", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.WRONG_AMOUNT),
    VC_WRONG_MID("VC-WRONG-MID", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.WRONG_MID),
    VC_STATE_MISMATCH("VC-STATE-MISMATCH", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.STATE_MISMATCH),
    VC_OTHER_ISSUER("VC-OTHER-ISSUER", "SIMISSB", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_UNSUPPORTED_TYPE("VC-UNSUPPORTED-TYPE", "SIMISSA", "SIMACQA", "SIMAUTH1", "PREPAID", CardScenario.AUTHENTICATE),
    VC_LOOKUP_TIMEOUT_ONCE("VC-LOOKUP-TIMEOUT-ONCE", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.LOOKUP_TIMEOUT_ONCE),
    VC_LOOKUP_5XX_ONCE("VC-LOOKUP-5XX-ONCE", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.LOOKUP_5XX_ONCE),

    // AUTH-001 §5.3 가상 VAN 시나리오 카드(AU-12 — 인증은 정상, VAN 동작은 van 모듈이 카드 참조로 정한다. Demo).
    VC_VAN_DECLINE("VC-VAN-DECLINE", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_VAN_LATE_RESPONSE("VC-VAN-LATE-RESPONSE", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_VAN_DROP_AFTER_APPROVE("VC-VAN-DROP-AFTER-APPROVE", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_VAN_DROP_BEFORE_RECORD("VC-VAN-DROP-BEFORE-RECORD", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_VAN_PENDING_THEN_APPROVE("VC-VAN-PENDING-THEN-APPROVE", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_VAN_DUPLICATE_RESPONSE("VC-VAN-DUPLICATE-RESPONSE", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_VAN_PARTIAL_APPROVAL("VC-VAN-PARTIAL-APPROVAL", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_VAN_CARDTYPE_MISMATCH("VC-VAN-CARDTYPE-MISMATCH", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_VAN_MONTHS_MISMATCH("VC-VAN-MONTHS-MISMATCH", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_VAN_FRAGMENTED("VC-VAN-FRAGMENTED", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_VAN_BAD_MAC("VC-VAN-BAD-MAC", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_VAN_INQUIRY_UNAVAILABLE_ONCE("VC-VAN-INQUIRY-UNAVAILABLE-ONCE", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),

    // CANCEL-001 §6.4 가상 VAN 취소 시나리오 카드(CN-25 — 인증·승인은 정상, 취소 동작만 van 모듈이 원 승인의 카드 참조로 정한다. Demo).
    VC_CXL_DECLINE("VC-CXL-DECLINE", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_CXL_LATE_RESPONSE("VC-CXL-LATE-RESPONSE", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_CXL_DROP_AFTER("VC-CXL-DROP-AFTER", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_CXL_DROP_BEFORE("VC-CXL-DROP-BEFORE", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_CXL_PARTIAL("VC-CXL-PARTIAL", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_CXL_BAD_MAC("VC-CXL-BAD-MAC", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_CXL_FRAGMENTED("VC-CXL-FRAGMENTED", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    VC_CXL_INQUIRY_UNAVAILABLE_ONCE("VC-CXL-INQUIRY-UNAVAILABLE-ONCE", "SIMISSA", "SIMACQA", "SIMAUTH1", "CREDIT", CardScenario.AUTHENTICATE),
    ;

    /** 이 카드의 [previousLookups]번째 다음 조회 동작. "한 번만" 카드는 첫 조회만 이상 동작한다(같은 결과로 수렴). */
    fun lookupBehaviour(previousLookups: Long): LookupBehaviour = when {
        previousLookups > 0 -> LookupBehaviour.NORMAL
        scenario == CardScenario.LOOKUP_TIMEOUT_ONCE -> LookupBehaviour.DELAY
        scenario == CardScenario.LOOKUP_5XX_ONCE -> LookupBehaviour.SERVER_ERROR
        else -> LookupBehaviour.NORMAL
    }

    companion object {
        fun fromRef(ref: String): VirtualCard? = entries.firstOrNull { it.ref == ref }
    }
}
