package com.paycore.master.merchant.domain

/** MID(계약 계정, D-MD-003). PAYMENT의 merchantAccountId와 같은 값. */
@JvmInline
value class MerchantAccountId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "merchantAccountId")
    }

    companion object {
        const val PREFIX = "mid"
    }
}
