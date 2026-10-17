package com.paycore.simulator.authentication.domain

/** Simulator 거절 사유(API 응답 code로 그대로 쓴다 — 고정 분류). */
enum class RejectionReason {
    INVALID_REQUEST,
    ENVIRONMENT_NOT_SUPPORTED,
    RETURN_URL_NOT_ALLOWED,
    STATE_REUSED_WITH_DIFFERENT_REQUEST,
    ALREADY_COMPLETED,
    NOT_FOUND,
    UNKNOWN_CARD,
}

/** Simulator 업무 거절. 메시지에 입력값을 넣지 않는다. */
class SimulatorRejection(val reason: RejectionReason) : RuntimeException(reason.name, null, false, false)
