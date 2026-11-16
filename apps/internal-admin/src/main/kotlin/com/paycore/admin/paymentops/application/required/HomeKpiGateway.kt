package com.paycore.admin.paymentops.application.required

/**
 * PAYMENT 운영 홈 KPI 호출 Port(ADMIN-HOME-KPI-001 §4.2·§5 — 구현 `adapter.integration.HttpPaymentOpsGateway`: **기존** ADMIN → PAYMENT 서명 채널·같은 키·`lookup` permit·timeout·
 * 응답 상한, 응답은 `PaymentResponseShape.HOME_KPI` + 이 요청의 답 검사(§4.3 fail-closed — 위반 = Unavailable)). **DB TX 안에서 부르면 `IllegalStateException`**.
 * 거래 조회 Port([PaymentReadGateway])와 따로 둔 것은 구현 세부(기존 Port 시험 대역을 바꾸지 않게 — 같은 구현 객체·같은 채널).
 */
interface HomeKpiGateway {
    fun homeKpis(): PaymentReadResult
}
