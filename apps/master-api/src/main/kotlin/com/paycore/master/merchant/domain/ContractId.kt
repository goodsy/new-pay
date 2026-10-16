package com.paycore.master.merchant.domain

@JvmInline
value class ContractId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "contractId")
    }

    companion object {
        const val PREFIX = "ctr"
    }
}
