package com.paycore.admin.merchantops.application.provided

/** 라벨 묶음 결과. [Found.labels]는 MASTER가 찾은 MID만(없는 MID는 키 없음). [Unavailable.reason]은 로그용 분류 문자열(값 없음). */
sealed interface MerchantLabelResult {
    data class Found(val labels: Map<String, MerchantLabel>) : MerchantLabelResult {
        override fun toString(): String = "Found(${labels.size})"
    }

    data class Unavailable(val failure: MerchantLabelFailure, val reason: String) : MerchantLabelResult
}
