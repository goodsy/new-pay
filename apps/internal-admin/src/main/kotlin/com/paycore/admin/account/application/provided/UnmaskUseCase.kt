package com.paycore.admin.account.application.provided

/**
 * 마스킹 해제 틀(AC10 — §4.5.3). 첫 실제 화면·대상 데이터 공급자는 후속 작업이다: 지금은 공급자가 없으므로 성공해도 [UnmaskResult.NoDataSource].
 * 순서(로컬 TX 하나): 요청자 행 잠금 → 권한(ACTIVE + `UNMASK` 역할 — 다른 역할과 독립)·세션 세대 → 사유 검사 → 직전 TOTP 단계 소비 → 감사 1행(`UNMASK_REQUESTED`, 사유 원문은
 * 감사 `reason` 열) → 커밋. TOTP 실패는 rollback 뒤 별도 TX 계수(§4.7.4 — 재인증 실패 합산).
 */
interface UnmaskUseCase {
    fun request(command: UnmaskRequest): UnmaskResult
}

/** [targetRef]는 대상 식별(고정 형식 — 영문·숫자·`_:-` 1~64자), [reason]은 1~500자 자유 문구(제어 문자 없음). */
data class UnmaskRequest(
    val actorId: String,
    val sessionGeneration: Long,
    val targetRef: String,
    val reason: String,
    val totpCode: String,
    val context: AdminRequestContext,
) {
    override fun toString(): String = "UnmaskRequest(actorId=$actorId, targetRef=$targetRef, totpCode=***)"
}

sealed interface UnmaskResult {
    /** 권한·사유·TOTP·감사 통과 — 공급자가 없어 원문 데이터 없음(웹 204). */
    data object NoDataSource : UnmaskResult

    /** 입력 형식(사유 없음·너무 김·대상 형식) — 웹 400. */
    data class Invalid(val code: String) : UnmaskResult

    data object Denied : UnmaskResult

    data object SessionReplaced : UnmaskResult

    data object StepUpFailed : UnmaskResult
}
