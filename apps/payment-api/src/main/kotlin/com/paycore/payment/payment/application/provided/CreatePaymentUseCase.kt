package com.paycore.payment.payment.application.provided

/**
 * CreatePayment 로컬 TX Use Case(CP-CONTRACT-001 §8–§11). 입력은 이미 형식 검증된 command이고(인증·shape·MID 일치·ID 생성은
 * 호출자 = [SubmitCreatePaymentUseCase]), 요청 fingerprint r1은 이 Use Case가 command 값으로 **저장된 버전에 맞춰** 계산한다(계약 §6.4·§7).
 * 이 Use Case는 PAYMENT DB 하나의 로컬 TX로 멱등 기록 → 주문 control → Payment → Outbox를 처리한다.
 * 외부 호출 없음. 응답은 commit 뒤에 만들어진다. 오류 HTTP 매핑은 CP-API-001.
 */
interface CreatePaymentUseCase {
    /**
     * eligibility는 이 Use Case가 required Port(`PaymentEligibilityPort`, 계약 §13)로 **command 값**(environment·인증 MID·Site·통화·
     * 결제수단)과 서버 시각으로 평가한다. 호출자가 판정을 주입할 수 없다(다른 Site·통화로 평가한 결과를 쓸 수 없음). 멱등 기록을 잡은 뒤,
     * 주문 판정 전에 같은 TX 안에서 한 번 평가하고(계약 §3.2 순서), 같은 키의 저장 결과를 재생할 때는 평가하지 않는다.
     * Eligible이 아니면 TX 전체를 rollback하고(멱등 결과 저장 안 함, 계약 §9 eligibility 행) [CreatePaymentResult.EligibilityRefused]를 준다.
     *
     * @throws CreatePaymentFailure 기술 실패(업무 결과 아님). 결과가 저장되지 않았거나(rollback) 저장 여부를 같은 키로 다시 확인해야 한다.
     */
    fun create(command: CreatePaymentCommand): CreatePaymentResult
}
