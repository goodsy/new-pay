package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.MerchantId

/**
 * MERCHANT-CODE-001 MC-1b-6(Reconciliation — CQRS 조회 측, jOOQ adapter.query): "첫 계약 승인 뒤 코드 변경" 사후 대사 질의. 가맹점마다 `master_change_history`의 `MerchantCodeChanged` 행 `id`가
 * 그 가맹점 대상 계약(`contract.merchant_id`)의 `ContractRevisionApproved` 행 `id`보다 큰 경우를 찾는다. 두 쓰기 모두 같은 merchant 행 잠금을 쥔 채 이력을 INSERT하므로
 * AUTO_INCREMENT `id` 순서 = 직렬화 순서다(벽시계 비교 아님). 정상이면 빈 목록 — 결과가 있으면 운영 대사 항목(사람 검토).
 */
interface MerchantCodeChangeAuditReader {
    fun changesAfterContractApproval(): List<MerchantId>
}
