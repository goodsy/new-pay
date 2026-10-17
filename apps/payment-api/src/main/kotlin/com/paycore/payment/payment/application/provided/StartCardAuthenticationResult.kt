package com.paycore.payment.payment.application.provided

/** 인증 시작 결과(AUTHN-001 §6.3·§7.4.1). */
sealed interface StartCardAuthenticationResult {
    /** 303 — 설정 Simulator 고객 origin 아래 거래 URL(응답 조작으로 임의 redirect 불가 — §13.5). */
    data class Redirect(val customerUrl: String) : StartCardAuthenticationResult

    /** 401 — 쿠키 없음·불일치·결제창 세션 종료·만료(쓰기 경로라 만료 시각 경과 세션은 EXPIRED(TTL_ELAPSED)로 기록). */
    data object SessionExpired : StartCardAuthenticationResult

    /** 422 — 카드사·Plan 불허(같은 화면 + 고정 안내), 상태 불변. */
    data object NotAllowed : StartCardAuthenticationResult

    /** 409 — Payment가 REQUESTED가 아니다(결제창 세션 닫음, Payment 불변). */
    data class PaymentNotAvailable(val failRedirect: String) : StartCardAuthenticationResult

    /** 410 — 결제창 시각 경과(Payment 아직 REQUESTED — control 미보유 경로라 Payment 불변, 자기 세션·결제창 세션 EXPIRED(PAYMENT_CLOSED)). */
    data class CheckoutTimeOver(val failRedirect: String) : StartCardAuthenticationResult

    /** 409 — 등록과 TX-B 사이 다른 창의 시작이 이 세션을 대체했다(변경 없음, §7.4.1). */
    data object Superseded : StartCardAuthenticationResult

    /** 503 — 인증기관 등록 실패·timeout(세션 CREATED 유지 + REGISTRATION_FAILED 1회). */
    data object RegistrationFailed : StartCardAuthenticationResult

    /** 503 — 저장소 일시 실패·commit 불명확. */
    data object Unavailable : StartCardAuthenticationResult
}
