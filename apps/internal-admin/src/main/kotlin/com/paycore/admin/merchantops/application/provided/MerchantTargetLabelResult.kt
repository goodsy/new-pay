package com.paycore.admin.merchantops.application.provided

/**
 * 화면 가맹점 라벨 결과(MERCHANT-CODE-001 §5.6.3). [Found.labels]의 키 = merchantId(화면 대응용 — 화면에 그리지 않는다), 없는 키는 빠진다.
 * [Unavailable]은 MASTER 실패 분류(응답은 HTTP 200 `"labels":"UNAVAILABLE"`·items `[]` — 화면 "—" + 안내 1줄).
 */
sealed interface MerchantTargetLabelResult {
    data class Found(val labels: Map<String, MerchantLabel>) : MerchantTargetLabelResult {
        override fun toString(): String = "Found(${labels.size})"
    }

    data class Unavailable(val failure: MerchantLabelFailure) : MerchantTargetLabelResult

    data object Forbidden : MerchantTargetLabelResult

    data object InvalidInput : MerchantTargetLabelResult

    data object RateLimited : MerchantTargetLabelResult
}
