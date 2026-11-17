package com.paycore.admin.merchantops.application.required

/**
 * 사업자 관리 지표 Port(BUSINESS-PROFILE-001 §11 Observability — 구현 Micrometer). label은 고정 분류뿐(값·ID·이름 없음).
 * 속도 제한·명령 결과·결과 불명은 기존 [MerchantOpsMetrics](`rate_limited{kind}`·`command{operation,result}`·`command_unknown{operation}`)를 쓴다.
 */
interface BusinessOpsMetrics {
    /** 번호 조회 결과(`paycore.admin.merchant_ops.business_lookup{result}` — FORBIDDEN·INVALID_INPUT·RATE_LIMITED·OK·UNAVAILABLE·AUDIT_UNAVAILABLE). */
    fun lookup(result: String)

    /** MASTER 400 `BUSINESS_VALIDATION_FAILED` — ADMIN 복제 규칙이 통과시킨 값을 MASTER가 거절했다(복제 규칙 불일치 신호 `paycore.admin.merchant_ops.business_rule_drift{operation}`). */
    fun ruleDrift(operation: String)
}
