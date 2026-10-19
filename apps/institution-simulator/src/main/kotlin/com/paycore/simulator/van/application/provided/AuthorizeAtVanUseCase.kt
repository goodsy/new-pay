package com.paycore.simulator.van.application.provided

import com.paycore.simulator.van.domain.VanAuthRequest
import com.paycore.simulator.van.domain.VanBehaviour
import com.paycore.simulator.van.domain.VanLedgerEntry

/**
 * 가상 VAN 승인(AUTH-001 §5.3). 결정은 원장에 커밋된 뒤 돌려준다 — 전송 계층은 이 결과를 받은 뒤에만 응답한다(원장 커밋이 응답보다 먼저, 성공 후 응답 유실 시나리오의 전제).
 * [clientId]는 프레임 HMAC 키가 정한 PG client다.
 */
interface AuthorizeAtVanUseCase {
    fun authorize(clientId: String, request: VanAuthRequest): VanAuthorizationResult
}

sealed interface VanAuthorizationResult {
    /** 원장 결정(새 결정이면 [behaviour]가 첫 응답의 전송 동작, 재응답·예약 확정이면 NORMAL). */
    data class Decided(val entry: VanLedgerEntry, val behaviour: VanBehaviour) : VanAuthorizationResult

    data class Pending(val pendingId: String) : VanAuthorizationResult

    data object Conflict : VanAuthorizationResult

    data object RejectedClosed : VanAuthorizationResult

    /** 응답 없이 연결을 닫는다(TEST 아님·등록 안 된 계정·기록 전 끊김 시나리오). 원장 기록 없음. */
    data object CloseWithoutRecord : VanAuthorizationResult
}
