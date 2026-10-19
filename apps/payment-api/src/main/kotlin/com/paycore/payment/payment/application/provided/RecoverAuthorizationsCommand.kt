package com.paycore.payment.payment.application.provided

/** 승인 회복 한 회차(AUTH-001 §6.1). [runId]는 회차 ID(로그 correlationId·이벤트 인과 값), [batchSize]는 이번 회차가 선점할 최대 일정 수. */
data class RecoverAuthorizationsCommand(val runId: String, val batchSize: Int) {
    init {
        require(runId.isNotBlank()) { "run id must not be blank" }
        require(batchSize >= 1) { "batch size must be positive" }
    }
}
