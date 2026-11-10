package com.paycore.master.merchant.application.provided

/** 기한 확인 결과(§9.4): 저장된(또는 재생된) 결정, 아니면 아직 기한 전(아무것도 저장하지 않았다 — 예약 행도 없음, m-c). */
sealed interface DeadlineConfirmationResult {
    data class Decided(val result: AdminCommandResult) : DeadlineConfirmationResult

    data object NotYetDue : DeadlineConfirmationResult
}
