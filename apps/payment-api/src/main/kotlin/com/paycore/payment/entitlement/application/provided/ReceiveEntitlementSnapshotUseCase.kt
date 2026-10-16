package com.paycore.payment.entitlement.application.provided

import com.paycore.payment.entitlement.domain.InboxOutcome
import com.paycore.payment.entitlement.domain.IncomingSnapshot

/**
 * MASTER 권리 Snapshot 수신(Inbox, MASTER-SNAPSHOT-001 명세 §5.1). 서명 검증·봉투 해석은 호출자(web adapter)가 TX 밖에서 끝낸다. 이 use case는
 * PAYMENT 로컬 TX 하나에서 head 잠금 → Inbox (consumer, eventId) 조회 → 적용 규칙 → Inbox 기록 + 업무 반영(head·revision별 내용·MID 소속)을
 * 함께 커밋하고, **커밋 뒤에만** 결과를 돌려준다(ACK = 내구 커밋). 커밋 단계 실패는 결과를 돌려주지 않는다([ReceiveFailure]) — MASTER가 같은
 * eventId로 다시 보내면 DUPLICATE 또는 APPLIED로 수렴한다.
 */
interface ReceiveEntitlementSnapshotUseCase {
    fun receive(envelope: InboundSnapshotEnvelope): ReceiveResult
}

/** 해석된 봉투 v1. [rawBody]는 받은 본문(원문 보존), [rawSha256]은 그 바이트의 SHA-256(소문자 hex). */
data class InboundSnapshotEnvelope(
    val eventType: String,
    val snapshot: IncomingSnapshot,
    val rawBody: String,
    val rawSha256: String,
)

sealed interface ReceiveResult {
    /**
     * 내구 커밋된 결과(새 처리 또는 같은 eventId 재전송의 DUPLICATE). [appliedRevision] = 지금 head revision. DUPLICATE이면 [originalOutcome] =
     * 처음 처리 때 저장한 outcome(review m-3 — 예: 처음이 UNSUPPORTED였으면 MASTER가 fail-closed 효력 확인에 쓴다).
     */
    data class Stored(val consumer: String, val outcome: InboxOutcome, val appliedRevision: Long, val originalOutcome: InboxOutcome? = null) : ReceiveResult

    /** 같은 eventId가 다른 본문으로 왔다(계약 오류 — 저장 없음, 수신 adapter는 409). */
    data object EventIdConflict : ReceiveResult
}

/** 결과를 알 수 없거나 일시적으로 처리하지 못했다(수신 adapter는 5xx — MASTER가 같은 eventId로 다시 보낸다). */
class ReceiveFailure(message: String, cause: Throwable?) : RuntimeException(message, cause)
