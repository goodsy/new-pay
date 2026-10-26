package com.paycore.admin.account.adapter.batch

import com.paycore.admin.account.application.provided.OneShotResult

/** 1회성 명령 종료 코드(§4.9 — 성공 0, 조건 불충족·경합 패배·출력 거부·재시도 가능은 서로 다른 비영 값). */
enum class OneShotExit(val code: Int) {
    SUCCESS(0),
    FAILED(1),
    USAGE(2),
    MODE_REFUSED(3),
    CONDITION_REFUSED(4),
    LOST(5),
    OUTPUT_REFUSED(6),
    OUTPUT_FAILED(7),
    RETRY_LATER(8),
    ;

    companion object {
        fun of(result: OneShotResult): OneShotExit = when (result) {
            is OneShotResult.Done -> SUCCESS
            is OneShotResult.Refused -> CONDITION_REFUSED
            OneShotResult.Lost -> LOST
            OneShotResult.RetryLater -> RETRY_LATER
            is OneShotResult.Invalid -> USAGE
        }
    }
}
