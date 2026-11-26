package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 정지·정지 해제·해지 기록(MERCHANT-FLOW-001 §4B.2 — BR-BIZ-336·339, 표 `business_contract_event`, INSERT 전용). 사건 순번 1, 2, …(계약 안).
 * '사유 및 비고' [reason] 필수(1~200 code point, 카드번호 패턴 거부 — [BusinessChangeReason]). 근거 참조 [evidence]는 해지만 선택으로 받는다(BR-BIZ-339).
 * 정지·정지 해제의 근거 필수 여부는 **Q-FLOW-38 답 전**이라 받지 않는다(DB CHECK와 같음 — 답이 나오면 FLOW-2c가 이 규칙과 CHECK를 함께 바꾼다).
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
        require(evidence == null || kind == BusinessContractEventKind.TERMINATE) { "only a TERMINATE event carries an evidence reference (Q-FLOW-38)" }
    }
}
