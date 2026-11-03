package com.paycore.payment.payment.adapter.web

/**
 * 결제창 화면 고정 문구(AUTHN-001 §6.3 — 내부 사유·예외·SQL·Snapshot 버전·기관 코드·URL 원문 없음, §12.5). [CheckoutShell] 오류 화면 데이터의 `message`로 보내고
 * React 화면이 텍스트로 그린다(CHECKOUT-REACT-001). 필터 단계(CSRF 거절)도 같은 shell 오류 화면을 쓴다.
 */
object CheckoutPages {
    const val CANNOT_OPEN = "결제창을 열 수 없습니다. 가맹점에서 다시 시도해 주세요."
    const val PAYMENT_NOT_AVAILABLE = "결제를 진행할 수 없습니다."
    const val SESSION_EXPIRED = "결제창 세션이 만료되었습니다."
    const val REQUEST_REJECTED = "요청을 처리할 수 없습니다."
    const val CHECKOUT_TIME_OVER = "결제 시간이 지났습니다."
    const val NOT_ALLOWED = "선택한 카드사 또는 할부를 사용할 수 없습니다."
    const val REGISTRATION_FAILED = "인증기관 연결에 실패했습니다. 다시 시도해 주세요."
    const val OTHER_WINDOW = "다른 창에서 인증을 시작했습니다."
    const val RESULT_UNVERIFIED = "인증 결과를 확인할 수 없습니다."
    const val RESULT_CHECKING = "인증 결과를 확인 중입니다."
    const val AUTHENTICATION_INCOMPLETE = "인증이 완료되지 않았습니다."
    const val AUTHENTICATION_TIME_OVER = "인증 시간이 지났습니다."
    const val TEMPORARY_ERROR = "일시적인 오류입니다."
}
