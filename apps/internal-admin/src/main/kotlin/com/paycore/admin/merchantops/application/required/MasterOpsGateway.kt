package com.paycore.admin.merchantops.application.required

import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria

/**
 * MASTER 내부 조회 API 호출 Port(MERCHANT-OPS-001 §4 — 구현 `adapter.integration.HttpMasterOpsGateway`: 서명·전용 permit·timeout·응답 상한·허용 필드 모양 검사).
 * **DB TX 안에서 부르면 `IllegalStateException`**. 경로 변수·본문 값은 호출자가 형식 검사한 값만 넣는다. 실패는 예외가 아니라 [MasterCallResult.Unavailable].
 */
interface MasterOpsGateway {
    fun search(criteria: MerchantSearchCriteria): MasterCallResult

    fun detail(merchantId: String): MasterCallResult

    fun history(merchantId: String, afterSeq: Long): MasterCallResult
}

sealed interface MasterCallResult {
    data class Found(val document: Map<String, Any?>) : MasterCallResult

    data object NotFound : MasterCallResult

    data class Unavailable(val reason: String) : MasterCallResult
}
