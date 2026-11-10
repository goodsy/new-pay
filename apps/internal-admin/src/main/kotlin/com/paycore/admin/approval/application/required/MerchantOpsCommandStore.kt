package com.paycore.admin.approval.application.required

import com.paycore.admin.approval.domain.MerchantOpsRequestSnapshot

/**
 * 가맹점 승인 요청 고정 값 저장 Port(MERCHANT-OPS-002 B2-2 `admin_merchant_ops_command` — INSERT-only, §14 기록 Store: 승인 Aggregate의 생성 불변 부속 기록). 호출자 TX 안에서만(MANDATORY).
 * - [insert]: 승인 행 INSERT와 같은 TX. 같은 승인에 두 번째면 PK 1062(기술 실패 그대로).
 * - [find]: 승인 TX1의 `pinned_target` 비교·실행 본문 재료(근거 참조), 회복 job, 근거 별도 조회.
 */
interface MerchantOpsCommandStore {
    fun insert(command: MerchantOpsRequestSnapshot)

    fun find(approvalId: String): MerchantOpsRequestSnapshot?
}
