package com.paycore.financial.acquiring.application.provided

/**
 * 재매입 실행(승인된 재매입을 새 버전 파일로 봉인). **D-ACQ-011: 입력 경로(ACQ-ADMIN-001) 전에는 실행할 수 없다** — 이 Use Case는 언제나 거절하고 아무것도 쓰지 않는다
 * (봉인 서비스는 generation ≥ 1 레코드를 만드는 경로가 없다). 실행 구현은 ACQ-ADMIN-001이 이 계약을 바꿀 때 함께 만든다.
 */
interface ExecuteReacquisitionUseCase {
    fun execute(approvalId: String): ReacquisitionExecutionResult
}

data class ReacquisitionExecutionResult(val executed: Boolean, val refusal: String)
