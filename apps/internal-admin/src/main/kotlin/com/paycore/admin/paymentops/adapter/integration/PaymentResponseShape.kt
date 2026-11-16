package com.paycore.admin.paymentops.adapter.integration

import tools.jackson.databind.JsonNode

/**
 * PAYMENT 거래 조회 응답 허용 필드 모양(TXN-OPS-001 §4 — fail-closed, MERCHANT-OPS-001 `MasterResponseShape`와 같은 방식 — 모듈 내부 구현이라 모듈마다 둔다). 객체는 **정해진 키 집합과 정확히
 * 같아야** 하고(알 수 없는 필드 = 거부: PAYMENT가 실수로 새 값을 내보내도 화면으로 흘리지 않는다), 값은 형식 정규식·code point 길이·정수 범위·불린·목록 길이 상한으로 검사한다. 통과하면 Kotlin 기본 값
 * (String·Long·Boolean·null·List·LinkedHashMap)으로 다시 만든다. 기관 내부 계정 값·운영자 사유·결제 key 필드는 어느 모양에도 없다. 목록 주문명 미리보기는 **10 code point 이하**만 통과한다.
 */
internal sealed interface PaymentResponseShape {
    /** 검사·변환. 위반이면 [IllegalArgumentException]. */
    fun read(node: JsonNode?): Any?

    class Obj(private val fields: Map<String, PaymentResponseShape>) : PaymentResponseShape {
        override fun read(node: JsonNode?): Map<String, Any?> {
            require(node != null && node.isObject) { "object" }
            require(node.propertyNames().toSet() == fields.keys) { "fields" }
            return fields.entries.associateTo(LinkedHashMap()) { (name, shape) -> name to shape.read(node.get(name)) }
        }
    }

    class Arr(private val item: PaymentResponseShape, private val max: Int) : PaymentResponseShape {
        override fun read(node: JsonNode?): List<Any?> {
            require(node != null && node.isArray && node.size() <= max) { "array" }
            return node.iterator().asSequence().map { item.read(it) }.toList()
        }
    }

    /** 문자열: 형식 정규식 + code point 길이 1..[maxCodePoints](정규식의 `{n}`은 UTF-16 단위라 보충 문자를 2로 센다 — 길이는 따로 센다). */
    class Text(private val format: Regex, private val maxCodePoints: Int = Int.MAX_VALUE) : PaymentResponseShape {
        override fun read(node: JsonNode?): String {
            require(node != null && node.isString) { "string" }
            return node.asString().also {
                require(format.matches(it)) { "format" }
                require(it.codePointCount(0, it.length) in 1..maxCodePoints) { "length" }
            }
        }
    }

    class Whole(private val min: Long) : PaymentResponseShape {
        override fun read(node: JsonNode?): Long {
            require(node != null && node.isIntegralNumber && node.canConvertToLong()) { "integer" }
            return node.asLong().also { require(it >= min) { "range" } }
        }
    }

    data object Flag : PaymentResponseShape {
        override fun read(node: JsonNode?): Boolean {
            require(node != null && node.isBoolean) { "boolean" }
            return node.asBoolean()
        }
    }

    /**
     * minor unit 정수의 10진 **문자열**(ADMIN-HOME-KPI-001 §4.1·§4.3 MINOR-7): `^(0|-?[1-9][0-9]{0,39})$` — `-0`·앞자리 0·소수점·지수·공백·JSON 숫자 거부, 최대 40자리. [signed]가
     * 아니면 음수도 거부. 문자열 그대로 돌려준다(Long·float로 바꾸지 않는다 — 화면도 Number로 바꾸지 않는다).
     */
    class MinorDecimal(private val signed: Boolean) : PaymentResponseShape {
        override fun read(node: JsonNode?): String {
            require(node != null && node.isString) { "string" }
            return node.asString().also { require((if (signed) SIGNED_DECIMAL else UNSIGNED_DECIMAL).matches(it)) { "minor decimal" } }
        }
    }

    class Nullable(private val inner: PaymentResponseShape) : PaymentResponseShape {
        override fun read(node: JsonNode?): Any? {
            require(node != null) { "missing" }
            return if (node.isNull) null else inner.read(node)
        }
    }

    companion object {
        private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
        private val PAYMENT_ID = Text(Regex("^pay_$ULID$"))
        private val ID = Text(Regex("^[a-z]{2,4}_$ULID$"))
        private val REF = Text(Regex("^[A-Za-z0-9._:-]{1,64}$"))
        private val ENVIRONMENT = Text(Regex("^(TEST|LIVE)$"))
        private val TIME = Text(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$"))
        private val DATE = Text(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))
        private val CODE = Text(Regex("^[A-Z][A-Z0-9_]{0,47}$"))
        private val CURRENCY = Text(Regex("^[A-Z]{3}$"))

        /** 표시용 주문명(CP-API-001 OrderNameRule — 제어문자 없음, 100 code point 이하). 목록 미리보기는 10 code point 이하(D-TXOPS-003). */
        private val ORDER_NAME = Text(Regex("^[^\\p{Cntrl}\\u2028\\u2029]+$"), maxCodePoints = 100)
        private val ORDER_NAME_PREVIEW = Text(Regex("^[^\\p{Cntrl}\\u2028\\u2029]+$"), maxCodePoints = 10)
        private val AMOUNT = Whole(1)
        private val NON_NEGATIVE = Whole(0)
        private const val MAX_ITEMS = 50
        private const val MAX_FACTS = 500
        private val SIGNED_DECIMAL = Regex("^(0|-?[1-9][0-9]{0,39})$")
        private val UNSIGNED_DECIMAL = Regex("^(0|[1-9][0-9]{0,39})$")

        /** 운영 홈 KPI 행 상한(ADMIN-HOME-KPI-001 §4.3·L-5 — 환경 2 × 통화 32). */
        private const val MAX_HOME_KPI_ITEMS = 64

        private fun obj(vararg fields: Pair<String, PaymentResponseShape>) = Obj(linkedMapOf(*fields))
        private fun opt(inner: PaymentResponseShape) = Nullable(inner)

        val LIST: PaymentResponseShape = obj(
            "items" to Arr(
                obj(
                    "paymentId" to PAYMENT_ID, "environment" to ENVIRONMENT, "merchantAccountId" to REF, "merchantOrderId" to REF,
                    "orderNamePreview" to opt(ORDER_NAME_PREVIEW), "orderNameTruncated" to Flag, "amountMinor" to AMOUNT, "currency" to CURRENCY, "status" to CODE,
                    "createdAt" to TIME,
                ),
                MAX_ITEMS,
            ),
            "nextCursor" to opt(obj("createdAt" to TIME, "paymentId" to PAYMENT_ID)),
        )

        /** 운영 홈 오늘 KPI(ADMIN-HOME-KPI-001 §4.1 — timeZone은 리터럴 `Asia/Seoul`, 금액은 minor unit 10진 문자열, 건수는 정수 ≥ 0). 이 요청의 답 검사는 [HomeKpiAnswer]. */
        val HOME_KPI: PaymentResponseShape = obj(
            "asOf" to TIME,
            "businessDate" to DATE,
            "timeZone" to Text(Regex("^Asia/Seoul$")),
            "from" to TIME,
            "items" to Arr(
                obj(
                    "environment" to ENVIRONMENT, "currency" to CURRENCY, "approvedAmountMinor" to MinorDecimal(signed = false),
                    "cancelledAmountMinor" to MinorDecimal(signed = false), "netApprovedAmountMinor" to MinorDecimal(signed = true), "approvedCount" to NON_NEGATIVE,
                ),
                MAX_HOME_KPI_ITEMS,
            ),
        )

        val DETAIL: PaymentResponseShape = obj(
            "payment" to obj(
                "paymentId" to PAYMENT_ID, "environment" to ENVIRONMENT, "merchantAccountId" to REF, "merchantSiteId" to REF, "merchantOrderId" to REF,
                "orderName" to opt(ORDER_NAME), "amountMinor" to AMOUNT, "currency" to CURRENCY, "paymentMethod" to CODE, "status" to CODE, "createdAt" to TIME,
            ),
            "onHold" to Flag,
            "terminationEvidence" to Arr(CODE, MAX_FACTS),
            "authorization" to opt(
                obj(
                    "authorizationId" to ID, "status" to CODE, "failureEvidence" to opt(CODE), "planKind" to CODE, "installmentMonths" to opt(Whole(2)),
                    "cardType" to CODE, "openedAt" to TIME,
                ),
            ),
            "transactions" to Arr(
                obj(
                    "transactionId" to ID, "type" to CODE, "amountMinor" to AMOUNT, "currency" to CURRENCY, "planKind" to opt(CODE), "installmentMonths" to opt(Whole(2)),
                    "cardType" to opt(CODE), "approvalNumber" to opt(REF), "providerTransactionId" to REF, "cancellationId" to opt(ID), "originalTransactionId" to opt(ID),
                    "cancelScope" to opt(CODE), "institutionTransactionDate" to DATE, "institutionBusinessDate" to opt(DATE), "confirmedAt" to TIME, "recordedBy" to CODE,
                ),
                MAX_FACTS,
            ),
            "cancellations" to Arr(
                obj(
                    "cancellationId" to ID, "initiatorKind" to CODE, "merchantCancelId" to opt(REF), "scope" to CODE, "status" to CODE, "failureEvidence" to opt(CODE),
                    "amountMinor" to AMOUNT, "currency" to CURRENCY, "openedAt" to TIME,
                ),
                MAX_FACTS,
            ),
            "returnBalance" to opt(
                obj(
                    "currency" to CURRENCY, "authorizedMinor" to AMOUNT, "completedMinor" to NON_NEGATIVE, "reservedMinor" to NON_NEGATIVE, "forcedMinor" to NON_NEGATIVE,
                    "revision" to NON_NEGATIVE,
                ),
            ),
        )
    }
}
