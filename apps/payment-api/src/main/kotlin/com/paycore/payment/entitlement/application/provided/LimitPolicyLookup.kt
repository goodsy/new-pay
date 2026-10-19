package com.paycore.payment.entitlement.application.provided

/**
 * 고정된 권리 revision의 한도 정책 값 조회(AUTH-001 AU-29). [entitlementVersion] = `es1:<merchantId>:<revision>`(승인에 고정된 값), [policyId] = MASTER 정책 행 ID. 불변 revision
 * 내용에서만 읽는다(head·현재 정책 아님). 없으면 null. 호출자 TX에 참여한다(MANDATORY).
 */
interface LimitPolicyLookup {
    fun find(entitlementVersion: String, mid: String, policyId: String): LimitTerms.Resolved?
}
