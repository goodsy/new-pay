package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 정지·정지 해제·해지 기록(MERCHANT-FLOW-001 §4B.2 — BR-BIZ-336·339, 표 `business_contract_event`, INSERT 전용). 사건 순번 1, 2, …(계약 안).
 * '사유 및 비고' [reason] 필수(1~200 code point, 카드번호 패턴 거부 — [BusinessChangeReason]). 근거 참조 [evidence]는 **세 종류 모두 선택**(BR-BIZ-339, 사용자 답 D-GOV-022 —
 * Q-FLOW-38 = 정지·정지 해제도 해지와 같은 규칙). DB CHECK `ck_bce_evidence_kind`는 V23이 없앴다(형식 CHECK는 그대로).
 */
data class BusinessContractEvent(
    val eventSeq: Int,
    val kind: BusinessContractEventKind,
    val reason: BusinessChangeReason,
    val evidence: ContractDocumentRef?,
    val actor: ActorRef,
    val occurredAt: Instant,
) {
    init {
        require(eventSeq >= 1) { "event sequence starts at 1" }
    }
}
