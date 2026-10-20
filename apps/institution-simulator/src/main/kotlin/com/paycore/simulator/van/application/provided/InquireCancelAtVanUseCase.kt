package com.paycore.simulator.van.application.provided

import com.paycore.simulator.van.domain.VanCancelEntry

/** 가상 VAN 원 취소 요청 ID 조회(CANCEL-001 §6.5). 무효과 종결은 원장에 커밋된 뒤 돌려준다. 모든 조회는 조회 기록(operation CANCEL)을 남긴다. */
interface InquireCancelAtVanUseCase {
    fun inquire(clientId: String, inquiry: VanCancelInquiryRequest): VanCancelInquiryResult
}

data class VanCancelInquiryRequest(
    val inquiryId: String,
    val originalRequestId: String,
    val institutionAccount: String,
    val environment: String,
    val closeIfAbsent: Boolean,
)

sealed interface VanCancelInquiryResult {
    data class Found(val entry: VanCancelEntry) : VanCancelInquiryResult

    data class NoEffectConfirmed(val closureId: String) : VanCancelInquiryResult

    data object NotFound : VanCancelInquiryResult

    data object Unavailable : VanCancelInquiryResult

    /** 응답 없이 연결을 닫는다(TEST 아님·등록 안 된 계정·재판정 상한). 기록 없음. */
    data object CloseWithoutRecord : VanCancelInquiryResult
}
