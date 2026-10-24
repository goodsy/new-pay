package com.paycore.simulator.merchant.application.provided

import com.paycore.simulator.merchant.domain.ReceiptOutcome
import com.paycore.simulator.merchant.domain.ReceivedEnvelope
import com.paycore.simulator.merchant.domain.ReceiverReply

/** 가상 가맹점 수신(WEBHOOK-001 §9). [envelope]는 web adapter가 본문에서 읽은 값(읽을 수 없으면 null) — 서명 검증을 통과한 뒤에만 쓰인다. */
fun interface ReceiveMerchantWebhookUseCase {
    fun receive(command: ReceiveMerchantWebhookCommand): ReceiveMerchantWebhookResult
}

class ReceiveMerchantWebhookCommand(val receiverId: String, val signatureHeader: String?, val body: ByteArray, val envelope: ReceivedEnvelope?)

sealed interface ReceiveMerchantWebhookResult {
    /** 모르는 수신기 — 404, 원장 행 없음. */
    data object UnknownReceiver : ReceiveMerchantWebhookResult

    /** 원장에 기록하고 [reply]로 응답한다(지연은 TX가 끝난 뒤). */
    data class Answered(val outcome: ReceiptOutcome, val reply: ReceiverReply) : ReceiveMerchantWebhookResult
}
