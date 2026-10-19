package com.paycore.simulator.van.domain

/** 가상 VAN 승인 판정 결과([VanAuthorization.judge]). application이 결과대로 원장·예약·증거 사용을 같은 TX에 기록한 뒤 응답한다. */
sealed interface VanJudgement {
    /** 같은 요청 ID·같은 payload: 기록된 결정을 그대로 다시 응답(새 결정 없음). */
    data class Replay(val entry: VanLedgerEntry) : VanJudgement

    /** 같은 요청 ID·다른 payload(원장·예약 불변). */
    data object Conflict : VanJudgement

    /** 무효과 종결 표지가 있는 요청 ID(늦게 도착한 원 요청 — 원장 불변). */
    data object RejectedClosed : VanJudgement

    /** TEST가 아닌 요청: 원장 기록 없이 연결을 닫는다. */
    data object Refused : VanJudgement

    /** 요청을 읽고 기록 없이 연결을 닫는다(VC-VAN-DROP-BEFORE-RECORD). */
    data object DropWithoutRecord : VanJudgement

    /** 새 결정: 원장 INSERT 뒤 응답. [consumesEvidence]면 같은 TX에 증거 사용을 기록한다(다른 요청 ID 재사용 차단). [behaviour]는 첫 응답의 전송 동작. */
    data class Decide(val entry: VanLedgerEntry, val behaviour: VanBehaviour, val consumesEvidence: Boolean) : VanJudgement

    /** 새 PENDING 예약(증거 사용 기록 포함). */
    data class Hold(val pending: VanPendingDecision) : VanJudgement

    /** 기존 예약이 아직 처리 중. */
    data class StillPending(val pending: VanPendingDecision) : VanJudgement

    /** 기존 예약을 결정으로 확정(원장 INSERT + 예약 RESOLVED). */
    data class ResolvePending(val pending: VanPendingDecision, val entry: VanLedgerEntry) : VanJudgement
}
