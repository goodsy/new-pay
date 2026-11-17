package com.paycore.admin.merchantops.application

/**
 * 사업자 관리 권한 상수(BUSINESS-PROFILE-001 §7.1 m-6 — 사용자 결정 **D-BIZ-006 ①**, 2026-11-17: 조회 = OPERATOR·APPROVER, 등록·수정·등록 완료 = OPERATOR 1인 — D-MOPS-004·006 ①).
 * `AdminAction` enum은 바꾸지 않는다(기존 merchantops Use Case 상수 방식). **상수 넷을 분리**하고 각 Use Case 메서드가 자기 상수만 쓴다 — 한 상수를 공유하면 이후 역할 변경이
 * 다른 동작까지 바꾼다. 판정 = 계정 현재 행 ACTIVE + 역할 집합 교집합(기존 `MerchantLookupService`·`MerchantRegistrationService` 방식).
 */
object BusinessPermissions {
    /** 목록·상세·이력·번호 조회(합친 BIZ-LOOKUP 포함 — D-BIZ-006 ①, MERCHANT-BIZ-LOOKUP-001 "권한 OPERATOR"를 사용자 결정으로 변경). */
    val VIEW: Set<String> = setOf("OPERATOR", "APPROVER")

    /** 생성(DRAFT·REGISTERED — D-BIZ-006 ①). */
    val CREATE: Set<String> = setOf("OPERATOR")

    /** 수정(D-BIZ-006 ①). */
    val UPDATE: Set<String> = setOf("OPERATOR")

    /** 등록 완료(DRAFT → REGISTERED — D-BIZ-006 ①). */
    val COMPLETE: Set<String> = setOf("OPERATOR")

    /**
     * 앞단(MERCHANT-OPS-001 §5.2·MERCHANT-BIZ-LOOKUP-001 §4.2 ①): 세션 계정이 업무 조회 역할조차 없으면 403 **감사 없음**(세션 없음·비활성·역할 없음 — BIZ-3-1).
     * 이 집합 안이지만 위 상수 밖이면 403 + 감사 `FORBIDDEN`(감사 대상 action만).
     */
    internal val FRONT: Set<String> = setOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN")
}
