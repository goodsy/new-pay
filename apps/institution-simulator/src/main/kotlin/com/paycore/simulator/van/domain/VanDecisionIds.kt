package com.paycore.simulator.van.domain

/**
 * 한 번의 판정이 쓸 수 있는 기관 식별자(application이 미리 만든다 — domain은 난수를 만들지 않는다). 결정 `vd_`·예약 `vp_`·종결 `vc_` + ULID(AU-30), 승인번호·기관 거래
 * 식별자(Demo 형식). 쓰지 않은 값은 버린다.
 */
data class VanDecisionIds(
    val decisionId: String,
    val pendingId: String,
    val closureId: String,
    val approvalNumber: String,
    val providerTransactionId: String,
) {
    init {
        require(decisionId.startsWith("vd_") && pendingId.startsWith("vp_") && closureId.startsWith("vc_")) { "VAN identifiers carry their prefixes" }
        require(approvalNumber.isNotEmpty() && providerTransactionId.isNotEmpty()) { "approval identifiers must not be empty" }
    }
}
