package com.paycore.simulator.merchant.application.provided

import com.paycore.simulator.merchant.domain.MerchantWebhookReceipt

/** 수신 원장 조회(E2E 확인용). 그 수신기를 소유한 PG client만 — 모르는 수신기·다른 client 수신기는 null(존재 비노출). */
fun interface MerchantWebhookReceiptQuery {
    fun receipts(clientId: String, receiverId: String): List<MerchantWebhookReceipt>?
}
