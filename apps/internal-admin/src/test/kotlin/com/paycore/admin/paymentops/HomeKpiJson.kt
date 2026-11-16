package com.paycore.admin.paymentops

/**
 * 운영 홈 KPI PAYMENT 응답 본문(ADMIN-HOME-KPI-001 §4.1 모양 — 시험 전용 Named Fixture). 기본값은 명세 예시 그대로(LIVE KRW 순승인 음수, TEST USD)이고, 위반 시험은 바꾸는 값만 인자로 준다.
 */
object HomeKpiJson {
    const val AS_OF = "2026-11-15T05:12:33.123Z"
    const val BUSINESS_DATE = "2026-11-15"
    const val FROM = "2026-11-14T15:00:00.000Z"

    /** 값은 이미 JSON 조각(문자열이면 따옴표 포함)이다 — 형식 위반(숫자 금액 등)을 그대로 넣을 수 있게. */
    fun item(
        environment: String = "\"LIVE\"",
        currency: String = "\"KRW\"",
        approved: String = "\"150000\"",
        cancelled: String = "\"170000\"",
        net: String = "\"-20000\"",
        count: String = "2",
        extra: String = "",
    ): String = "{\"environment\":$environment,\"currency\":$currency,\"approvedAmountMinor\":$approved,\"cancelledAmountMinor\":$cancelled," +
        "\"netApprovedAmountMinor\":$net,\"approvedCount\":$count$extra}"

    val USD_TEST: String = item("\"TEST\"", "\"USD\"", "\"1250\"", "\"0\"", "\"1250\"", "1")

    fun doc(
        items: List<String> = listOf(item(), USD_TEST),
        asOf: String = AS_OF,
        businessDate: String = BUSINESS_DATE,
        timeZone: String = "Asia/Seoul",
        from: String = FROM,
        extra: String = "",
    ): String = "{\"asOf\":\"$asOf\",\"businessDate\":\"$businessDate\",\"timeZone\":\"$timeZone\",\"from\":\"$from\",\"items\":[${items.joinToString(",")}]$extra}"
}
