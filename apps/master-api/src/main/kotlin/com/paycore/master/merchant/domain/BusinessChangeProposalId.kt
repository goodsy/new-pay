package com.paycore.master.merchant.domain

/** 사업자 중요정보 변경 제안 ID(BIZ-CRIT-APPROVAL-001 §3.2): `bcp_<ULID>`. 발급은 Application(ULID 생성기). */
@JvmInline
value class BusinessChangeProposalId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "proposalId")
    }

    companion object {
        const val PREFIX = "bcp"
    }
}
