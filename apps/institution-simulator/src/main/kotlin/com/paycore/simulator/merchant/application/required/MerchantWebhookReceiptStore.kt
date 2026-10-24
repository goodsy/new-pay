package com.paycore.simulator.merchant.application.required

import com.paycore.simulator.merchant.domain.MerchantWebhookReceipt

/**
 * 가상 가맹점 수신 원장 Port(append-only — 수정·삭제 연산 없음, DB 권한 SELECT·INSERT). 호출자 TX 안에서만(MANDATORY).
 * 같은 (수신기, eventId)의 ACCEPTED는 하나뿐이다(DB UNIQUE — 동시 첫 수신 경합은 [DuplicateMerchantReceipt], 호출자가 새 TX에서 재판정).
 */
interface MerchantWebhookReceiptStore {
    fun append(receipt: MerchantWebhookReceipt)

    /** 그 이벤트의 앞선 검증된 전달 수(ACCEPTED·DUPLICATE·SCENARIO_REFUSED). */
    fun countVerified(receiverId: String, eventId: String): Int

    fun hasAccepted(receiverId: String, eventId: String): Boolean

    /** 수신 순서(오래된 것부터) 최대 [limit]행. */
    fun list(receiverId: String, limit: Int): List<MerchantWebhookReceipt>
}
