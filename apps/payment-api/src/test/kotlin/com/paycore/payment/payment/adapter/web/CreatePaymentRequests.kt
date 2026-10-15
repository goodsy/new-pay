package com.paycore.payment.payment.adapter.web

/**
 * CreatePayment 요청 본문 Named Fixture(Demo 값 — 운영 MID·Site 아님). 본문은 JSON 원문 문자열로 만든다(파서·HTTP 시험이 원문 표기 자체를
 * 다루므로). 위반 본문은 이름이 의도를 드러내는 함수로만 만든다(Test Fixture Policy: Invalid Fixture의 의도 명시).
 */
object CreatePaymentRequests {
    const val MID = "mid_01HZY3K8Q4M2N6P8R0T2V4X6Z8"
    const val SITE = "site_01HZY3K8Q4M2N6P8R0T2V4X6Z9"
    const val ORDER_ID = "order-0001"

    /** 정상 본문. [orderNameJson]이 있으면 그 JSON 원문을 그대로 넣는다(null·객체 등 위반 Fixture용). */
    fun valid(
        mid: String = MID,
        site: String = SITE,
        orderId: String = ORDER_ID,
        amount: Long = 10_000,
        currency: String = "KRW",
        method: String = "CARD",
        orderName: String? = "Demo order",
        orderNameJson: String? = null,
    ): String = buildString {
        append("{\"merchantAccountId\":").append(quote(mid))
        append(",\"merchantSiteId\":").append(quote(site))
        append(",\"merchantOrderId\":").append(quote(orderId))
        append(",\"amount\":").append(amount)
        append(",\"currency\":").append(quote(currency))
        append(",\"paymentMethod\":").append(quote(method))
        when {
            orderNameJson != null -> append(",\"orderName\":").append(orderNameJson)
            orderName != null -> append(",\"orderName\":").append(quote(orderName))
        }
        append('}')
    }

    /** 같은 내용, 다른 필드 순서·공백. */
    fun reordered(): String =
        "{ \"currency\" : \"KRW\", \"amount\" : 10000, \"paymentMethod\":\"CARD\", \"merchantOrderId\":\"$ORDER_ID\"," +
            " \"merchantSiteId\":\"$SITE\", \"merchantAccountId\":\"$MID\" }"

    /** amount 자리에 JSON 원문 [raw]를 넣는다(소수·지수·문자열 등 위반 표기). */
    fun withRawAmount(raw: String): String = valid().replace("\"amount\":10000", "\"amount\":$raw")

    /** 정상 본문에 [fieldJson](`"name":value`)을 하나 더 넣는다(모르는 필드·중복 키). */
    fun withExtraField(fieldJson: String): String = valid().removeSuffix("}") + ",$fieldJson}"

    /** [field] 값을 JSON 문자열 원문 [escapedContent](escape 포함, 따옴표 없이)로 바꾼다. */
    fun withEscapedString(field: String, escapedContent: String): String {
        val body = valid()
        val pattern = Regex("\"$field\":\"[^\"]*\"")
        require(pattern.containsMatchIn(body)) { "field not in the valid body" }
        // 람다 치환: 치환 문자열의 `\`·`$`를 정규식 치환 문법으로 해석하지 않게 한다(escape 원문 그대로).
        return body.replace(pattern) { "\"$field\":\"$escapedContent\"" }
    }

    /** [field]를 뺀 본문(필수 필드 누락). */
    fun without(field: String): String = valid().replace(Regex(",?\"$field\":(\"[^\"]*\"|[0-9]+)"), "").replace("{,", "{")

    private fun quote(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
