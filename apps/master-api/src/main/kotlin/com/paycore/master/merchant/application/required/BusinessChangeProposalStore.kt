package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.BusinessChangeDigest
import com.paycore.master.merchant.domain.BusinessChangeProposal
import com.paycore.master.merchant.domain.BusinessChangeProposalId

/**
 * 사업자 중요정보 변경 제안 저장 Port(BIZ-CRIT-APPROVAL-001 §3.1 — 제안은 사업자 Aggregate가 아닌 **프로토콜 기록**이라 `…Store` ③, ARCHITECTURE-CONVENTIONS §14).
 * INSERT 전용(runtime 권한 SELECT·INSERT만 — V27). 구현 `JpaBusinessChangeProposalStore`.
 *
 * - [insert]: 제안 행 하나 + 저장 digest([digest] — MASTER 키 `h1:`) + 요청 기록 ID. 즉시 flush(PK·FK 위반이 Port 안에서 드러난다).
 * - [find]: 제안 ID로 한 행(실행 TX — 사업자 잠금 뒤 읽기만). 없으면 null. 저장 digest는 표시·대조용이고 실행 판정은 원문 재계산 값으로 한다.
 */
interface BusinessChangeProposalStore {
    fun insert(proposal: BusinessChangeProposal, digest: BusinessChangeDigest, requestRecordId: String)

    fun find(id: BusinessChangeProposalId): BusinessChangeProposal?
}
