package com.paycore.payment.payment.application.provided

/**
 * 결제창 만료 주기 작업 Use Case(AUTHN-001 §7.5.4, D-AUTHN-005·008). 후보(결제창 시각이 지난 REQUESTED — 비권위 조회)마다 로컬 TX 하나에서 control(X) → payment(X)를 잡고
 * 공통 inline 만료 단계로 만료한다. 한 후보의 실패는 그 건만 rollback되고 다음 후보를 계속한다(다음 회차에 다시 후보). 여러 인스턴스가 동시에 돌아도 DB 잠금이 직렬화한다.
 */
interface ExpireElapsedRequestedPaymentsUseCase {
    fun expireElapsed(command: ExpireElapsedRequestedPaymentsCommand): ExpiryRunResult
}
