package com.paycore.simulator.van.domain

/** 가상 VAN 취소 판정 결과([VanCancellation.judge]). application이 결정을 원장에 기록·커밋한 뒤 응답한다. */
sealed interface VanCancelJudgement {
    /** 같은 요청 ID·같은 payload: 기록된 결정을 그대로 다시 응답(새 결정 없음). */
    data class Replay(val entry: VanCancelEntry) : VanCancelJudgement

    /** 같은 요청 ID·다른 payload(원장 불변). */
    data object Conflict : VanCancelJudgement

    /** 무효과 종결 표지가 있는 취소 요청 ID(늦게 도착한 원 요청 — 원장 불변). */
    data object RejectedClosed : VanCancelJudgement

    /** TEST가 아니거나 보존기간 밖 요청: 원장 기록 없이 연결을 닫는다. */
    data object Refused : VanCancelJudgement

    /** 요청을 읽고 기록 없이 연결을 닫는다(VC-CXL-DROP-BEFORE). */
    data object DropWithoutRecord : VanCancelJudgement

    /** 새 결정: 원장 INSERT 뒤 응답. [behaviour]는 첫 응답의 전송 동작. */
    data class Decide(val entry: VanCancelEntry, val behaviour: VanCancelBehaviour) : VanCancelJudgement
}
