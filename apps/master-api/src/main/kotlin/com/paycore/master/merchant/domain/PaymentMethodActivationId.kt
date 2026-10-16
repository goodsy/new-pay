package com.paycore.master.merchant.domain

@JvmInline
value class PaymentMethodActivationId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "activationId")
    }

    companion object {
        const val PREFIX = "act"
    }
}
