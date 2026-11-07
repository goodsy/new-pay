package com.paycore.admin.financialops.application.required

/**
 * FINANCIAL 매입 조작 채널 HTTP Port(ACQ-ADMIN-001 B1-A §4 — 구현 `adapter.integration.HttpFinancialOpsGateway`). **DB TX 안에서 부르면 `IllegalStateException`**.
 * - [targets]: 미리 보기 — **조회 키**·조회 permit. 200은 허용 모양 + 요청-응답 대응(같은 파일, 요청한 순번 집합)일 때만 Found.
 * - [execute]·[decision]·[confirmDeadline]: **조작 키**·조작 permit(대기 0 — 없으면 FINANCIAL을 부르지 않고 Failed `FINANCIAL_OPERATION_BUSY`). 200 본문은 결정 모양 또는 결정 없음 모양만.
 */
interface AcquiringOpsGateway {
    fun targets(fileId: String, seqs: List<Int>): FinancialCallResult

    /** [approvalId]는 본문의 승인 ID — 200 결정 본문의 승인 ID와 대조한다(요청-응답 대응, B1-A 리뷰 n-3). */
    fun execute(approvalId: String, body: ByteArray): OperationCallResult

    fun decision(approvalId: String): OperationCallResult

    fun confirmDeadline(approvalId: String, body: ByteArray): OperationCallResult
}

sealed interface OperationCallResult {
    /** 200 결정 본문(허용 모양 검사 뒤 Kotlin 기본 값으로 다시 만든 문서). */
    data class Decision(val document: Map<String, Any?>) : OperationCallResult

    /** 200 결정 없음(`{"decision":"NONE",...}`). */
    data object NoDecision : OperationCallResult

    /** 200이 아닌 HTTP 응답([errorCode] = 본문 `error`가 형식에 맞으면). */
    data class Status(val status: Int, val errorCode: String?) : OperationCallResult

    /** 호출 실패·permit 없음·200 본문 해석 불가. */
    data class Failed(val reason: String) : OperationCallResult
}
