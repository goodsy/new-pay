/**
 * paymentfact 모듈의 공개 API(ARCHITECTURE-CONVENTIONS §3). 다른 모듈(acquiring)은 이 패키지만 쓸 수 있다: 결제 사실 매입 조회(PaymentFactAcquisitionSource —
 * DTO만, domain 타입 없음)와 D-FFR-003 보류 해제 일괄 처리(SweepOriginalUnknownHoldsUseCase). ACQUIRING-001에서 처음 다른 모듈이 쓰게 되어 named interface를 둔다.
 */
@org.springframework.modulith.NamedInterface("provided")
package com.paycore.financial.paymentfact.application.provided;
