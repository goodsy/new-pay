package com.paycore.admin.merchantops.application.provided

/** 표시 라벨 — 상호([name], MASTER `DisplayName` 규칙)와 **현재** 가맹점 코드([code]). 화면 표기 "<상호> · <가맹점 코드>"(D-ADMIN-UI-004 ③). */
data class MerchantLabel(val name: String, val code: String) {
    override fun toString(): String = "MerchantLabel(***)"
}
