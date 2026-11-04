package com.paycore.admin.paymentops

/**
 * TXN-OPS-001 PAYMENT 거래 조회 응답 본문 시험 값(src/test 전용 — PAYMENT `AdminPaymentController` 모양 §3.3·§3.4를 그대로 적은 Named Fixture). 값은 Demo 형식이고 금액은
 * 서로 맞게 적었다(승인 10,000 − 완료 취소 3,000).
 */
object TransactionJson {
    const val ORDER_FULL = "가나다라마바사아자차카타파하"
    const val ORDER_PREVIEW = "가나다라마바사아자차"

    fun item(paymentId: String, createdAt: String = "2026-11-01T01:02:03.456Z", preview: String? = ORDER_PREVIEW, truncated: Boolean = true, orderId: String = "order-77") =
        "{\"paymentId\":\"$paymentId\",\"environment\":\"TEST\",\"merchantAccountId\":\"mid-demo-1\",\"merchantOrderId\":\"$orderId\"," +
            "\"orderNamePreview\":${preview?.let { "\"$it\"" } ?: "null"},\"orderNameTruncated\":$truncated,\"amountMinor\":10000,\"currency\":\"KRW\",\"status\":\"AUTHORIZED\"," +
            "\"createdAt\":\"$createdAt\"}"

    fun list(vararg items: String, next: String? = null) =
        "{\"items\":[${items.joinToString(",")}],\"nextCursor\":${next?.let { "{\"createdAt\":\"2026-11-01T01:02:03.456Z\",\"paymentId\":\"$it\"}" } ?: "null"}}"

    /** 상세 본문. [extra]는 payment 객체 끝에 덧붙는 필드(허용 필드 모양 위반 시험용). */
    fun detail(paymentId: String, extra: String = "") =
        "{\"payment\":{\"paymentId\":\"$paymentId\",\"environment\":\"TEST\",\"merchantAccountId\":\"mid-demo-1\",\"merchantSiteId\":\"demo-site-001\"," +
            "\"merchantOrderId\":\"order-77\",\"orderName\":\"$ORDER_FULL\",\"amountMinor\":10000,\"currency\":\"KRW\",\"paymentMethod\":\"CARD\",\"status\":\"AUTHORIZED\"," +
            "\"createdAt\":\"2026-11-01T01:02:03.456Z\"$extra}," +
            "\"onHold\":false,\"terminationEvidence\":[]," +
            "\"authorization\":{\"authorizationId\":\"pau_01JC0000000000000000000001\",\"status\":\"AUTHORIZED\",\"failureEvidence\":null,\"planKind\":\"LUMP_SUM\"," +
            "\"installmentMonths\":null,\"cardType\":\"CREDIT\",\"openedAt\":\"2026-11-01T01:02:04.000Z\"}," +
            "\"transactions\":[" +
            "{\"transactionId\":\"ptx_01JC0000000000000000000001\",\"type\":\"AUTHORIZATION\",\"amountMinor\":10000,\"currency\":\"KRW\",\"planKind\":\"LUMP_SUM\"," +
            "\"installmentMonths\":null,\"cardType\":\"CREDIT\",\"approvalNumber\":\"12345678\",\"providerTransactionId\":\"SIMPTX-000111\",\"cancellationId\":null," +
            "\"originalTransactionId\":null,\"cancelScope\":null,\"institutionTransactionDate\":\"2026-11-01\",\"institutionBusinessDate\":null," +
            "\"confirmedAt\":\"2026-11-01T01:02:05.000Z\",\"recordedBy\":\"RESOLUTION\"}," +
            "{\"transactionId\":\"ptx_01JC0000000000000000000002\",\"type\":\"CANCEL\",\"amountMinor\":3000,\"currency\":\"KRW\",\"planKind\":null," +
            "\"installmentMonths\":null,\"cardType\":null,\"approvalNumber\":null,\"providerTransactionId\":\"SIMPTX-000112\",\"cancellationId\":\"pcn_01JC0000000000000000000001\"," +
            "\"originalTransactionId\":\"ptx_01JC0000000000000000000001\",\"cancelScope\":\"PARTIAL\",\"institutionTransactionDate\":\"2026-11-01\",\"institutionBusinessDate\":\"2026-11-02\"," +
            "\"confirmedAt\":\"2026-11-01T02:00:00.000Z\",\"recordedBy\":\"RESOLUTION\"}]," +
            "\"cancellations\":[{\"cancellationId\":\"pcn_01JC0000000000000000000001\",\"initiatorKind\":\"OPERATOR\",\"merchantCancelId\":null,\"scope\":\"PARTIAL\"," +
            "\"status\":\"COMPLETED\",\"failureEvidence\":null,\"amountMinor\":3000,\"currency\":\"KRW\",\"openedAt\":\"2026-11-01T01:59:00.000Z\"}]," +
            "\"returnBalance\":{\"currency\":\"KRW\",\"authorizedMinor\":10000,\"completedMinor\":3000,\"reservedMinor\":0,\"forcedMinor\":0,\"revision\":2}}"
}
