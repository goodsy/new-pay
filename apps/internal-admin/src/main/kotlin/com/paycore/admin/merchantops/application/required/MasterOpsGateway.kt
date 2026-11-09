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

    /**
     * MASTER 등록 명령(MERCHANT-OPS-002 §4.1 — 같은 서명기·키, D-MOPS-005). 조회와 **별개의 쓰기 permit**(대기 0), 매 호출 새 `t`, 응답 상한 4 KiB, 허용 모양 fail-closed.
     * 결과는 값([MasterCommandResult]) — timeout·연결 끊김·해석 불가는 [MasterCommandResult.Unknown](효과 단정 없음). **DB TX 안에서 부르면 `IllegalStateException`**.
     */
    fun command(command: MasterCommand): MasterCommandResult
}

sealed interface MasterCallResult {
    data class Found(val document: Map<String, Any?>) : MasterCallResult

    data object NotFound : MasterCallResult

    data class Unavailable(val reason: String) : MasterCallResult
}
