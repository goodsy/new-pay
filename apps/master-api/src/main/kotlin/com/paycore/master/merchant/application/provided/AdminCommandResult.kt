package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.MasterRejection

/**
 * 관리 명령 결과. 같은 키·같은 내용 재요청은 저장된 최초 결과를 [replayed]=true로 돌려준다(성공·업무 거절 모두). 같은 키·다른 내용은
 * [IdempotencyKeyReused]이며 아무것도 저장하지 않는다.
 */
sealed interface AdminCommandResult {
    /** [reference] = 만든 대상 ID(등록·발급은 새 ID, 승인은 `contractId#revisionNo`). */
    data class Succeeded(val reference: String, val replayed: Boolean) : AdminCommandResult

    /** 업무 거절(저장·재생). [reference] = 판단 근거 대상(예: 이미 있는 Default MID·같은 등록번호의 Business). */
    data class Rejected(val rejection: MasterRejection, val reference: String?, val replayed: Boolean) : AdminCommandResult

    data object IdempotencyKeyReused : AdminCommandResult
}

/** 결과를 바꾸지 않는 기술 실패. 호출자는 같은 키로 다시 요청해 결과를 확인한다(새 키 금지 — 결과 불명확 시 중복 효과 위험). */
sealed class AdminCommandFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    /** 같은 키의 앞 요청이 아직 커밋하지 않았다(잠금 대기 timeout). */
    class RequestInProgress(cause: Throwable) : AdminCommandFailure("an earlier request with the same key is still in progress", cause)

    class TemporarilyUnavailable(cause: Throwable) : AdminCommandFailure("temporarily unavailable", cause)

    /** commit 단계 실패: 커밋 여부를 알 수 없다. 같은 키로 다시 확인한다. */
    class OutcomeUnknown(cause: Throwable) : AdminCommandFailure("commit outcome unknown", cause)

    /** 서버 생성 ID([kind] = 어느 식별자 제약)가 기존 행과 충돌했다(사용자 입력 충돌이 아니다). 같은 키로 다시 요청하면 새 ID로 처리된다. */
    class IdentifierCollision(val kind: String) : AdminCommandFailure("server-generated identifier collided: $kind", null)
}
