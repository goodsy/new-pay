package com.paycore.master.merchant.domain

@JvmInline
value class ContractServiceAgreementId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "agreementId")
    }

    companion object {
        const val PREFIX = "csa"
    }
}
