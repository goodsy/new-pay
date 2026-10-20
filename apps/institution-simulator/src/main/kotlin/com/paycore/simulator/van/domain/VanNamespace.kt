package com.paycore.simulator.van.domain

/**
 * 기관 요청 namespace(AUTH-001 §5.3-1 — client·environment·institutionAccount·operation·requestId). 원장 UNIQUE와 PENDING 예약 UNIQUE가 이 값이다: 한 namespace의
 * 기관 사실(결정 또는 무효과 종결)은 원장 행 하나뿐이다.
 */
data class VanNamespace(
    val clientId: String,
    val environment: String,
    val institutionAccount: String,
    val operation: String,
    val requestId: String,
) {
    companion object {
        const val AUTHORIZE = "AUTHORIZE"

        /** 취소 namespace(CANCEL-001 §6.3-1 — 취소 원장 `van_cancel_entry`의 UNIQUE). */
        const val CANCEL = "CANCEL"

        fun of(clientId: String, request: VanAuthRequest) = VanNamespace(clientId, request.environment, request.institutionAccount, AUTHORIZE, request.requestId)

        fun of(clientId: String, request: VanCancelRequest) = VanNamespace(clientId, request.environment, request.institutionAccount, CANCEL, request.requestId)
    }
}
