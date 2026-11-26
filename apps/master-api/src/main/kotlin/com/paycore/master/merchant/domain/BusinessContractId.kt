package com.paycore.master.merchant.domain

/**
 * 사업자 계약 ID(MERCHANT-FLOW-001 §4B.2 — BR-BIZ-337 "사업자 계약 ID"): (사업자, 계약 순번). 순번은 사업자 안 1, 2, …(사업자 행 잠금 아래 부여 — 최신 = 순번 최대).
 * 이관 행도 SQL로 같은 형식을 만들 수 있어 ULID가 필요 없다(사업자 계약은 사업자 밖으로 나가지 않는다).
 */
data class BusinessContractId(val businessId: BusinessId, val contractSeq: Int) {
    init {
        require(contractSeq >= 1) { "contract sequence starts at 1" }
    }
}
