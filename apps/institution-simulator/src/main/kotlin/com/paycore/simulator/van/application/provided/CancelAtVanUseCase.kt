package com.paycore.simulator.van.application.provided

import com.paycore.simulator.van.domain.VanCancelBehaviour
import com.paycore.simulator.van.domain.VanCancelEntry
import com.paycore.simulator.van.domain.VanCancelRequest

/**
 * 가상 VAN 취소(CANCEL-001 §6.3). 결정은 원장에 커밋된 뒤 돌려준다 — 전송 계층은 이 결과를 받은 뒤에만 응답한다(성공 후 응답 유실 시나리오의 전제). 거절도 기록한다.
 * [clientId]는 프레임 HMAC 키가 정한 PG client다.
 */
interface CancelAtVanUseCase {
    fun cancel(clientId: String, request: VanCancelRequest): VanCancelResult
}

sealed interface VanCancelResult {
    /** 원장 결정(새 결정이면 [behaviour]가 첫 응답의 전송 동작, 재응답이면 NORMAL). */
    data class Decided(val entry: VanCancelEntry, val behaviour: VanCancelBehaviour) : VanCancelResult

    data object Conflict : VanCancelResult

    data object RejectedClosed : VanCancelResult

    /** 응답 없이 연결을 닫는다(TEST 아님·등록 안 된 계정·보존기간 밖·기록 전 끊김·재판정 상한). 원장 기록 없음. */
    data object CloseWithoutRecord : VanCancelResult
}
