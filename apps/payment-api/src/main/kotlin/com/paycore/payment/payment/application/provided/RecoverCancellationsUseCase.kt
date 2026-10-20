package com.paycore.payment.payment.application.provided

/**
 * 취소 회복 Use Case(CANCEL-001 §7 — AUTH-001 설계 복제, CN-24): 점검 시각이 된 취소 회복 일정을 선점해 PREPARED fencing·dispatch lease 만료 표시·원 **취소** 요청 ID 조회·대사 이관을 한다.
 * 조회는 DB TX 밖이고, 반영은 취소 TX2와 같은 단계([com.paycore.payment.payment.application.CancellationResultService])다. 새 기관 취소 요청 ID·새 취소 요청을 만들지 않는다(K-14).
 * 이관된 취소 UNKNOWN에는 임시 출구가 없다(D-CANCEL1-003 — RECON 전 확정 Use Case 없음).
 */
interface RecoverCancellationsUseCase {
    fun recover(command: RecoverCancellationsCommand): RecoveryRunResult
}

/** 취소 회복 한 회차. [runId]는 회차 ID(로그 correlationId·이벤트 인과 값), [batchSize]는 이번 회차가 선점할 최대 일정 수. */
data class RecoverCancellationsCommand(val runId: String, val batchSize: Int) {
    init {
        require(runId.isNotBlank()) { "run id must not be blank" }
        require(batchSize >= 1) { "batch size must be positive" }
    }
}
