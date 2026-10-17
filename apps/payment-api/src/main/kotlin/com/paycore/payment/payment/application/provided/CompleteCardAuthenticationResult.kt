package com.paycore.payment.payment.application.provided

/** 인증 결과 반영 결과(AUTHN-001 §6.3·§7.3). */
sealed interface CompleteCardAuthenticationResult {
    /** 303 — VERIFIED(R11) 또는 같은 결과 참조 재요청(P2 DUPLICATE): successUrl + paymentId·authenticationRef. */
    data class Verified(val successRedirect: String) : CompleteCardAuthenticationResult

    /** 200 실패 화면 — 같은 결제창에서 다시 시도 또는 failUrl(code=AUTHENTICATION_FAILED·USER_CANCELLED). 세션 FAILED. */
    data class Failed(val code: CheckoutFailCode, val failRedirect: String) : CompleteCardAuthenticationResult

    /** 503 + Retry-After — 조회 확인 불가(R3): 세션 IN_PROGRESS 유지, 같은 복귀 URL로 재시도. */
    data object Unverifiable : CompleteCardAuthenticationResult

    /** 410 — 세션이 이미 FAILED·EXPIRED(P1) 또는 세션 TTL 경과(P6). 다시 시도 가능. */
    data object Ignored : CompleteCardAuthenticationResult

    /** 410 — Payment가 REQUESTED가 아니거나 결제창 시각 경과(P5): 세션·결제창 세션 EXPIRED(PAYMENT_CLOSED), Payment 불변. */
    data class CheckoutClosed(val code: CheckoutFailCode, val failRedirect: String) : CompleteCardAuthenticationResult

    /** 400 — query 형식 오류·state에 맞는 세션 없음·등록 미완료(P4)·증거 충돌(P3)·결과 참조 재사용. 상태 불변. */
    data object Rejected : CompleteCardAuthenticationResult

    /** 401 — 결제창 세션 쿠키 없음·불일치·종료·만료. */
    data object SessionExpired : CompleteCardAuthenticationResult

    /** 503 — 저장소 일시 실패·commit 불명확(같은 복귀 URL 재요청이 같은 결과로 수렴 — 조회는 멱등). */
    data object Unavailable : CompleteCardAuthenticationResult
}
