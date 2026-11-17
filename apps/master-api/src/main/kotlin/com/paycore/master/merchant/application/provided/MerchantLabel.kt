package com.paycore.master.merchant.application.provided

/** 가맹점 라벨(표시 전용 — 상호는 `merchant.display_name`, 코드는 가장 큰 배정 seq, MERCHANT-CODE-001 §5.6.3). [merchantId]는 ADMIN 대응용(화면 미표시). */
data class MerchantLabel(val merchantId: String, val merchantName: String, val merchantCode: String) {
    override fun toString(): String = "MerchantLabel(***)"
}
