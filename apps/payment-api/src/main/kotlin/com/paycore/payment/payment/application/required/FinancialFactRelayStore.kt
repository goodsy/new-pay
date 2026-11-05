package com.paycore.payment.payment.application.required

import java.time.Instant

/** Relay가 집은 FINANCIAL_FACT Outbox 한 건(봉투 v1 재료 — FIN-FACT-RELAY-001 §4.1). [payloadJson]은 Outbox JSON 열을 읽은 문자열 그대로다(재전송마다 같은 바이트). */
data class FinancialFactRelayEnvelope(
    val outboxId: Long,
    val eventId: String,
    val effectKey: String,
    val eventType: String,
    val schemaVersion: Int,
    val eventCategory: String,
    val producer: String,
    val aggregateType: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val occurredAt: Instant,
    val attempts: Int,
    val payloadJson: String,
) {
    override fun toString(): String = "FinancialFactRelayEnvelope(outboxId=$outboxId, eventId=$eventId, eventType=$eventType, attempts=$attempts)"
}

/**
 * FINANCIAL Inbox 내구 커밋 확인(ACK). [outcome]은 응답 그대로(APPLIED·HELD·UNSUPPORTED·DUPLICATE), [originalOutcome]은 DUPLICATE일 때 FINANCIAL이 처음 저장한 결과.
 */
data class FinancialFactAck(val consumer: String, val outcome: String, val originalOutcome: String?, val ackedAt: Instant) {
    /** FINANCIAL이 이 사실에 대해 처음 내린 결과(전달 상태에 남긴다). */
    val firstOutcome: String get() = originalOutcome ?: outcome
}

enum class FinancialFactRelayMark {
    /** 이 Relay의 claim으로 상태를 바꿨다(ACK는 claim과 무관하게 반영 — 전달은 끝났다). */
    MARKED,

    /** 이미 종결(DELIVERED·QUARANTINED)이었다 — 상태를 바꾸지 않는다(ACK면 ACK 기록만 추가). */
    ALREADY_FINAL,

    /** claim이 만료돼 다른 인스턴스가 다시 집었다(재시도·격리 표시를 덮어쓰지 않는다). */
    CLAIM_LOST,
}

/**
 * Relay 전달 상태 저장 Port(CONVENTIONS §14 Store ③ 프로토콜 기록 — FIN-FACT-RELAY-001 §4.4). 모든 메서드는 호출자 TX 필수(MANDATORY). Outbox는 publish_status·published_at만
 * 바꾼다(D-FFR-001 — [markDelivered]에서 PENDING → PUBLISHED). 가맹점 알림 열·payload는 건드리지 않는다.
 *
 * 한 회차: [discover](미전달(publish_status = PENDING) FINANCIAL_FACT Outbox 행에 PENDING 전달 행을 만든다) → [claim](`FOR UPDATE SKIP LOCKED` + lease 표시) →
 * (TX 밖 HTTP) → 행마다 [markDelivered]·[markRetry]·[markQuarantined], lease가 모자라 보내지 않은 행은 [releaseClaims]. 삭제 경로 없음.
 */
interface FinancialFactRelayStore {
    /** 새 FINANCIAL_FACT Outbox 행(최대 [limit])의 전달 행을 만든다(이미 있으면 그대로). 만든 행 수. */
    fun discover(now: Instant, limit: Int): Int

    /** PENDING·다음 시도 시각 도래·claim 없음/만료 행을 Outbox id 순으로 잠그고 claim([owner], [claimUntil])을 표시한다. */
    fun claim(owner: String, now: Instant, claimUntil: Instant, limit: Int): List<FinancialFactRelayEnvelope>

    /** DELIVERED + ACK 기록 + Outbox publish_status = PUBLISHED(같은 TX — D-FFR-001). 이미 종결이어도 ACK 기록은 추가(감사). */
    fun markDelivered(outboxId: Long, owner: String, ack: FinancialFactAck): FinancialFactRelayMark

    fun markRetry(outboxId: Long, owner: String, nextAttemptAt: Instant, failureCode: String, at: Instant): FinancialFactRelayMark

    /** FINANCIAL이 내용을 거절(400·409·422) → QUARANTINED(완료 아님 — 경보 대상, 재전송하지 않는다). */
    fun markQuarantined(outboxId: Long, owner: String, failureCode: String, at: Instant): FinancialFactRelayMark

    /**
     * 이 소유자가 claim했지만 보내지 않은 행의 claim을 푼다(시도 수·다음 시도 시각은 그대로 — 다음 회차·다른 인스턴스가 바로 집을 수 있다). 다른 소유자로 넘어간 행·종결 행은
     * 바꾸지 않는다. 푼 행 수.
     */
    fun releaseClaims(outboxIds: List<Long>, owner: String, at: Instant): Int
}
