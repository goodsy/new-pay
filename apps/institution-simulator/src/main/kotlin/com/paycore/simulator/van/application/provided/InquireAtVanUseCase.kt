package com.paycore.simulator.van.application.provided

import com.paycore.simulator.van.domain.VanLedgerEntry

/** 가상 VAN 원 요청 ID 조회(AUTH-001 §5.4). 무효과 종결·예약 확정은 원장에 커밋된 뒤 돌려준다. 모든 조회는 조회 기록을 남긴다. */
interface InquireAtVanUseCase {
    fun inquire(clientId: String, inquiry: VanInquiryRequest): VanInquiryResult
}

data class VanInquiryRequest(
    val inquiryId: String,
    val originalRequestId: String,
    val institutionAccount: String,
    val environment: String,
    val closeIfAbsent: Boolean,
)

sealed interface VanInquiryResult {
    data class Found(val entry: VanLedgerEntry) : VanInquiryResult

    data class Pending(val pendingId: String) : VanInquiryResult

    data class NoEffectConfirmed(val closureId: String) : VanInquiryResult

    data object NotFound : VanInquiryResult

    data object Unavailable : VanInquiryResult

    /** 응답 없이 연결을 닫는다(TEST 아님·등록 안 된 계정). 기록 없음. */
    data object CloseWithoutRecord : VanInquiryResult
}
