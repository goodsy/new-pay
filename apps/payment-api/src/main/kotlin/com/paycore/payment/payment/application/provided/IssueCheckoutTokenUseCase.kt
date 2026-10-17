package com.paycore.payment.payment.application.provided

/**
 * 결제창 토큰 발급 Use Case(AUTHN-001 §5, TX-I). 효과 멱등이다(§5.4 — 멱등키 재생 아님): 반복 호출은 "유효 토큰이 최신 하나"로 수렴한다. 결제창 시각이 지난 REQUESTED Payment는
 * inline 만료를 **커밋**한 뒤 [CheckoutTokenRejection.PAYMENT_TERMINATED]로 답한다(판정 U-3, n-d). 저장소 일시 실패·commit 불명확은
 * [CheckoutTokenRejection.CHECKOUT_UNAVAILABLE](원문이 전달되지 않았으므로 재요청이 안전 — 새 토큰이 이전 것을 대체한다).
 */
interface IssueCheckoutTokenUseCase {
    fun issue(command: IssueCheckoutTokenCommand): IssueCheckoutTokenResult
}
