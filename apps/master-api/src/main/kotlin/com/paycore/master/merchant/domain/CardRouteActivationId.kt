package com.paycore.master.merchant.domain

@JvmInline
value class CardRouteActivationId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "cardRouteActivationId")
    }

    companion object {
        const val PREFIX = "cra"
    }
}
