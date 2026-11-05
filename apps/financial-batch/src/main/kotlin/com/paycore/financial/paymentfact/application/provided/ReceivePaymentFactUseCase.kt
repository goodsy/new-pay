package com.paycore.financial.paymentfact.application.provided

import com.paycore.financial.paymentfact.domain.PaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFactInboxOutcome
import java.time.Instant

/**
 * PAYMENT 결제 사실 수신(Inbox — FIN-FACT-RELAY-001 §4.2, D-ACQ-001). 서명 검증·봉투 해석은 호출자(web adapter)가 TX 밖에서 끝낸다. 이 Use Case는 FINANCIAL 로컬 TX 하나에서
 * 결제별 직렬화 행 잠금 → Inbox(event_id·effect_key) 조회 → 투영 판정 → Inbox 기록 + 사실 투영 + 보류를 함께 커밋하고, **커밋 뒤에만** 결과를 돌려준다(ACK = 내구 커밋).
 * 커밋 단계 실패는 결과를 돌려주지 않는다([ReceivePaymentFactFailure]) — PAYMENT가 같은 eventId로 다시 보내면 DUPLICATE(처음 결과) 또는 처음 처리로 수렴한다.
 */
interface ReceivePaymentFactUseCase {
    fun receive(envelope: InboundPaymentFact): ReceivePaymentFactResult
}

/**
 * 해석된 봉투 v1. [fact]는 지원하는 (eventType, schemaVersion)일 때만 있다(null = UNSUPPORTED — 원문만 보존). [rawBody]는 받은 본문(원문 보존), [rawSha256]은 그 바이트의
 * SHA-256(소문자 hex — 같은 eventId 재전송의 동일성 판정), [signingKeyId]는 검증에 쓴 kid(감사).
 */
data class InboundPaymentFact(
    val eventId: String,
    val effectKey: String,
    val eventType: String,
    val schemaVersion: Int,
    val producer: String,
    val aggregateType: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val occurredAt: Instant,
    val fact: PaymentFact?,
    val rawBody: String,
    val rawSha256: String,
    val signingKeyId: String,
) {
    override fun toString(): String = "InboundPaymentFact(eventId=$eventId, eventType=$eventType, schemaVersion=$schemaVersion)"
}

sealed interface ReceivePaymentFactResult {
    /**
     * 내구 커밋된 결과. 새 처리면 [outcome] = 이번 결과, [duplicate] = false. 같은 eventId·같은 본문 재전송이면 [duplicate] = true이고 [outcome] = 처음 저장한 결과
     * (응답 `outcome=DUPLICATE`, `originalOutcome` = 이 값).
     */
    data class Stored(val outcome: PaymentFactInboxOutcome, val duplicate: Boolean) : ReceivePaymentFactResult

    /** 같은 eventId가 다른 본문으로 왔다(계약 오류 — 저장 없음, 수신 adapter는 409). */
    data object EventIdConflict : ReceivePaymentFactResult

    /** 다른 eventId가 이미 기록된 업무 효과(effect key)를 다시 주장했다(계약 오류 — 저장 없음, 409). */
    data object EffectKeyConflict : ReceivePaymentFactResult
}

/** 결과를 알 수 없거나 일시적으로 처리하지 못했다(수신 adapter는 503 — PAYMENT가 같은 eventId로 다시 보낸다). */
class ReceivePaymentFactFailure(message: String, cause: Throwable?) : RuntimeException(message, cause)
