package com.paycore.master.merchant.domain

/** Site 적용 확인 기록 ID(D-MD-016, MASTER-SNAPSHOT-001). */
@JvmInline
value class SiteActivationConfirmationId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "confirmationId")
    }

    companion object {
        const val PREFIX = "sac"
    }
}
