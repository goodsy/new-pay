package com.paycore.payment.payment.application.provided

/**
 * 승인 회복 Use Case(AUTH-001 §6): 점검 시각이 된 회복 일정을 선점해 PREPARED fencing·dispatch lease 만료 표시·원 Attempt 조회·대사 이관을 한다.
 * 조회는 DB TX 밖이고, 반영은 confirm TX2와 같은 단계([com.paycore.payment.payment.application.AuthorizationResultService])다. 새 기관 요청 ID·새 승인 요청을 만들지 않는다.
 */
interface RecoverAuthorizationsUseCase {
    fun recover(command: RecoverAuthorizationsCommand): RecoveryRunResult
}
