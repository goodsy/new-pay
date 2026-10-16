package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.ActorRef

/**
 * 관리 명령 공통 맥락. [correlationId]는 로그·이벤트 상관 ID(업무 키 아님). 명령 시각은 받지 않는다 — 서버 `MasterClock` Port가 정한다
 * (review m-3 결정: 시각은 fingerprint 대상이 아니므로 요청 필드로 둘 이유가 없다).
 */
data class AdminRequest(
    val idempotencyKey: AdminIdempotencyKey,
    val actor: ActorRef,
    val correlationId: String,
) {
    init {
        require(correlationId.length in 1..MAX_CORRELATION && correlationId.all { it in '!'..'~' }) { "correlationId must be 1..128 printable ASCII" }
    }

    private companion object {
        const val MAX_CORRELATION = 128
    }
}
