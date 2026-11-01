package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.MerchantAccountId

/**
 * MID의 정책 Assignment 내용 상태(DEMO-SEED-001 §4.5 — `demo-credential` 사전 조건). 구현은 entitlement 공개 조회(provided)를 호출자 PAYMENT 로컬 TX에서
 * 읽는다(`EntitlementMidPolicyProfileAdapter`). MID가 최신 Snapshot에 없으면 null. 저장소 일시 실패는 [PersistenceFailure].
 */
interface MidPolicyProfilePort {
    fun find(mid: MerchantAccountId): MidPolicyContents?
}

/** 최신 Snapshot의 그 MID 정책 Assignment 내용 상태들. [allDemo]는 하나 이상이고 전부 DEMO일 때만 참(UNDEFINED·0개는 거짓 — m5). */
data class MidPolicyContents(val demoCount: Int, val otherCount: Int) {
    val allDemo: Boolean get() = demoCount > 0 && otherCount == 0
}
