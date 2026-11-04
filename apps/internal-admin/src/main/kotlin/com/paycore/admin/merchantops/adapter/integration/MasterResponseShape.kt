package com.paycore.admin.merchantops.adapter.integration

import tools.jackson.databind.JsonNode

/**
 * MASTER 응답 허용 필드 모양(MERCHANT-OPS-001 §4 — fail-closed). 객체는 **정해진 키 집합과 정확히 같아야** 하고(알 수 없는 필드 = 거부: MASTER가 실수로 새 값을 내보내도 화면으로
 * 흘리지 않는다), 값은 형식 정규식·정수 범위·불린·목록 길이 상한으로 검사한다. 통과하면 Kotlin 기본 값(String·Long·Boolean·null·List·LinkedHashMap)으로 다시 만든다.
 * `institutionMerchantRef`·`detail`·등록번호 평문 필드는 어느 모양에도 없다.
 */
internal sealed interface MasterResponseShape {
    /** 검사·변환. 위반이면 [IllegalArgumentException]. */
    fun read(node: JsonNode?): Any?

    class Obj(private val fields: Map<String, MasterResponseShape>) : MasterResponseShape {
        override fun read(node: JsonNode?): Map<String, Any?> {
            require(node != null && node.isObject) { "object" }
            require(node.propertyNames().toSet() == fields.keys) { "fields" }
            return fields.entries.associateTo(LinkedHashMap()) { (name, shape) -> name to shape.read(node.get(name)) }
        }
    }

    class Arr(private val item: MasterResponseShape, private val max: Int) : MasterResponseShape {
        override fun read(node: JsonNode?): List<Any?> {
            require(node != null && node.isArray && node.size() <= max) { "array" }
            return node.iterator().asSequence().map { item.read(it) }.toList()
        }
    }

    class Text(private val format: Regex) : MasterResponseShape {
        override fun read(node: JsonNode?): String {
            require(node != null && node.isString) { "string" }
            return node.asString().also { require(format.matches(it)) { "format" } }
        }
    }

    class Whole(private val min: Long) : MasterResponseShape {
        override fun read(node: JsonNode?): Long {
            require(node != null && node.isIntegralNumber && node.canConvertToLong()) { "integer" }
            return node.asLong().also { require(it >= min) { "range" } }
        }
    }

    data object Flag : MasterResponseShape {
        override fun read(node: JsonNode?): Boolean {
            require(node != null && node.isBoolean) { "boolean" }
            return node.asBoolean()
        }
    }

    class Nullable(private val inner: MasterResponseShape) : MasterResponseShape {
        override fun read(node: JsonNode?): Any? {
            require(node != null) { "missing" }
            return if (node.isNull) null else inner.read(node)
        }
    }

    companion object {
        private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
        private val ID = Text(Regex("^[a-z]{2,4}_$ULID$"))
        private val MERCHANT_ID = Text(Regex("^mer_$ULID$"))
        private val BUSINESS_ID = Text(Regex("^biz_$ULID$"))
        private val TIME = Text(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$"))
        private val CODE = Text(Regex("^[A-Za-z][A-Za-z0-9_]{0,47}$"))
        private val NAME = Text(Regex("^[^\\p{Cntrl}]{1,200}$"))
        private val MASKED = Text(Regex("^[0-9]{3}\\*{7}$"))
        private val CHANNEL = Text(Regex("^[a-z0-9._-]{1,253}$"))
        private val INSTITUTION_CODE = Text(Regex("^[A-Z0-9]{1,32}$"))
        private val CURRENCY = Text(Regex("^[A-Z]{3}$"))
        private val ACTOR = Text(Regex("^[A-Za-z0-9._:@-]{1,64}$"))
        private val COUNT = Whole(0)
        private const val MAX_ITEMS = 1_000

        private fun obj(vararg fields: Pair<String, MasterResponseShape>) = Obj(linkedMapOf(*fields))
        private fun list(item: MasterResponseShape) = Arr(item, MAX_ITEMS)
        private fun opt(inner: MasterResponseShape) = Nullable(inner)

        val DIRECTORY: MasterResponseShape = obj(
            "items" to Arr(
                obj(
                    "merchantId" to MERCHANT_ID, "merchantName" to NAME, "businessId" to BUSINESS_ID, "businessName" to NAME,
                    "registrationNumberMasked" to MASKED, "lifecycle" to CODE, "entitlementRevision" to COUNT, "midCount" to COUNT, "createdAt" to TIME,
                ),
                max = 50,
            ),
            "nextCursor" to opt(MERCHANT_ID),
        )

        val HISTORY: MasterResponseShape = obj(
            "items" to Arr(
                obj(
                    "seq" to Whole(1), "occurredAt" to TIME, "operation" to CODE, "aggregateType" to CODE, "aggregateId" to ID, "changeType" to CODE,
                    "actorRef" to ACTOR,
                ),
                max = 50,
            ),
            "nextCursor" to opt(Whole(1)),
        )

        val DETAIL: MasterResponseShape = obj(
            "merchant" to obj("merchantId" to MERCHANT_ID, "name" to NAME, "lifecycle" to CODE, "entitlementRevision" to COUNT, "createdAt" to TIME),
            "business" to obj("businessId" to BUSINESS_ID, "name" to NAME, "registrationNumberMasked" to MASKED, "lifecycle" to CODE),
            "sites" to list(
                obj(
                    "merchantSiteId" to ID, "channelType" to CODE, "channelIdentifier" to CHANNEL, "lifecycle" to CODE,
                    "review" to opt(obj("decision" to CODE, "decidedAt" to TIME)),
                ),
            ),
            "contracts" to list(
                obj(
                    "contractId" to ID, "createdAt" to TIME,
                    "revisions" to list(
                        obj(
                            "revisionNo" to Whole(1), "scopeKind" to CODE, "effectiveFrom" to TIME, "effectiveTo" to opt(TIME), "approvedAt" to opt(TIME),
                            "scopeSiteIds" to list(ID),
                        ),
                    ),
                    "agreements" to list(obj("agreementId" to ID, "serviceKind" to CODE, "addedAt" to TIME)),
                ),
            ),
            "accounts" to list(
                obj(
                    "merchantAccountId" to ID, "lifecycle" to CODE, "isDefault" to Flag, "issuingContractId" to ID, "issuingAgreementId" to ID,
                    "issuingRevisionNo" to Whole(1), "issuedAt" to TIME,
                    "assignments" to list(
                        obj(
                            "assignmentId" to ID, "merchantSiteId" to ID, "role" to CODE, "status" to CODE, "effectiveFrom" to TIME, "effectiveTo" to opt(TIME),
                        ),
                    ),
                    "paymentMethods" to list(obj("activationId" to ID, "paymentMethod" to CODE, "status" to opt(CODE), "registeredAt" to TIME)),
                    "currencies" to list(
                        obj("enablementId" to ID, "currencyCode" to CURRENCY, "effectiveFrom" to TIME, "effectiveTo" to opt(TIME), "endedAt" to opt(TIME)),
                    ),
                    "cardRoutes" to list(
                        obj(
                            "activationId" to ID, "paymentMethod" to CODE, "acquirerCode" to INSTITUTION_CODE, "routeRef" to INSTITUTION_CODE,
                            "status" to CODE, "registeredAt" to TIME,
                        ),
                    ),
                ),
            ),
            "limits" to list(
                obj(
                    "policyId" to ID, "scopeKind" to CODE, "agreementId" to opt(ID), "merchantAccountId" to opt(ID), "currencyCode" to CURRENCY,
                    "perTxKind" to CODE, "perTxAmount" to opt(COUNT), "dailyKind" to CODE, "dailyAmount" to opt(COUNT), "effectiveFrom" to TIME,
                    "effectiveTo" to opt(TIME), "endedAt" to opt(TIME),
                ),
            ),
            "propagation" to opt(
                obj(
                    "latestRevision" to COUNT, "latestRevisionIssuedAt" to TIME, "latestAckRevision" to opt(COUNT), "latestAckAt" to opt(TIME),
                    "effectConfirmedAt" to opt(TIME), "effectBasis" to opt(CODE),
                ),
            ),
        )
    }
}
