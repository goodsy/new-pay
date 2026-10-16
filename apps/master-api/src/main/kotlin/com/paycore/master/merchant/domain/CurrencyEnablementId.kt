package com.paycore.master.merchant.domain

/** 통화 활성화 기록 ID(D-MD-018, MASTER-SNAPSHOT-001). */
@JvmInline
value class CurrencyEnablementId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "enablementId")
    }

    companion object {
        const val PREFIX = "cur"
    }
}
