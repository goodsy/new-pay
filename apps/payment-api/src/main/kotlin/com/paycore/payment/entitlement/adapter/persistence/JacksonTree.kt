package com.paycore.payment.entitlement.adapter.persistence

import com.paycore.payment.entitlement.domain.JsonValue
import tools.jackson.databind.JsonNode

/** Jackson 노드 → domain [JsonValue](domain은 Jackson을 모른다). 정수가 아닌 숫자는 계약 밖이라 거절한다. */
internal object JacksonTree {
    fun toJsonValue(node: JsonNode): JsonValue = when {
        node.isNull -> JsonValue.JNull
        node.isObject -> JsonValue.JObject(node.properties().associate { (k, v) -> k to toJsonValue(v) })
        node.isArray -> JsonValue.JArray((0 until node.size()).map { toJsonValue(node.get(it)) })
        node.isString -> JsonValue.JString(node.stringValue())
        node.isBoolean -> JsonValue.JBool(node.booleanValue())
        node.isIntegralNumber && node.canConvertToLong() -> JsonValue.JNumber(node.longValue())
        else -> throw com.paycore.payment.entitlement.domain.SnapshotContractViolation("unsupported JSON value")
    }
}
