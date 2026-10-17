package com.paycore.payment.payment.application.provided

/**
 * 결제창 화면 조회(AUTHN-001 §6.2·§6.3 — `CheckoutSessionQuery`·`CheckoutViewQuery`를 한 provided 조회로 둔다, §24.12). 표시 값은 비권위 jOOQ 조회이고 판정은 POST 처리의
 * 잠금 TX가 다시 한다. 쿠키 비밀은 digest로만 대조한다(원문 저장·로그 없음).
 */
interface CheckoutPageQuery {
    fun page(request: CheckoutPageRequest): CheckoutPageResult

    /** 결제창 세션 값이 없는 응답(오류 화면 등)의 CSP `form-action` 기본 origin(설정 Simulator 고객 origin — m-9). */
    fun defaultFormActionOrigins(): List<String>
}
