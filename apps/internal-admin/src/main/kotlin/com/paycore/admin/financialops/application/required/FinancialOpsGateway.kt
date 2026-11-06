package com.paycore.admin.financialops.application.required

import com.paycore.admin.financialops.application.provided.AcquisitionFileSearchCriteria
import com.paycore.admin.financialops.application.provided.AcquisitionRecordSearchCriteria

/**
 * FINANCIAL 내부 조회 API 호출 Port(ACQ-VIEW-001 §4.6 — 구현 `adapter.integration.HttpFinancialOpsGateway`: 서명·전용 permit·timeout·응답 상한·허용 필드 모양 검사·
 * 요청-응답 대응 검사). **DB TX 안에서 부르면 `IllegalStateException`**. 경로 변수·본문 값은 호출자가 형식 검사한 값만 넣는다. 실패는 예외가 아니라 [FinancialCallResult.Unavailable].
 */
interface FinancialOpsGateway {
    fun search(criteria: AcquisitionFileSearchCriteria): FinancialCallResult

    fun detail(fileId: String): FinancialCallResult

    fun records(fileId: String, criteria: AcquisitionRecordSearchCriteria): FinancialCallResult
}

sealed interface FinancialCallResult {
    data class Found(val document: Map<String, Any?>) : FinancialCallResult

    data object NotFound : FinancialCallResult

    data class Unavailable(val reason: String) : FinancialCallResult
}
