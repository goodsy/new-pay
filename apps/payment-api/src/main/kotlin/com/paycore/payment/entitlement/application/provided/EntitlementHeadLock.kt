package com.paycore.payment.entitlement.application.provided

/**
 * 권리 투영 head 공유 잠금(AUTH-001 rev 0.3 m-9). 호출자 TX에 참여한다(MANDATORY). MID의 소유 Merchant(비잠금 조회) head를 `FOR SHARE`로 잡는다 — 없으면 아무것도 잡지 않는다.
 * 판정은 하지 않는다(뒤의 [AuthorizationTermsQuery]가 같은 TX에서 같은 head를 다시 공유 잠금으로 읽는다).
 */
interface EntitlementHeadLock {
    /** @throws EntitlementEvaluationFailure 저장소 일시 실패. */
    fun lockHeadForShare(mid: String)
}
