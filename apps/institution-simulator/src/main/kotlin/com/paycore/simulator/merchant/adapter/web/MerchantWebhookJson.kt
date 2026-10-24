package com.paycore.simulator.merchant.adapter.web

import com.paycore.simulator.merchant.domain.MerchantWebhookReceipt
import com.paycore.simulator.merchant.domain.ReceivedEnvelope
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 가상 가맹점 수신기 JSON(WEBHOOK-001 §4.2.0 봉투 읽기 + 원장 조회 응답). 봉투는 `eventId`·`type`(문자열)·`sequence`(정수 ≥ 1)·`environment`(문자열)만 읽는다 — 하나라도 없거나
 * 형식이 다르면 null(서명 통과 후 REJECTED_BODY). 원장 응답은 없는 값의 키를 생략한다(본문은 저장하지 않으므로 응답에도 없다).
 */
internal object MerchantWebhookJson {
    const val MAX_BODY_BYTES = 64 * 1024
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val TYPE = Regex("^[a-z_.]{1,40}$")
    private val ENVIRONMENT = Regex("^[A-Z]{1,8}$")
    private val mapper = JsonMapper.builder().build()
    private val nodes = JsonNodeFactory.instance

    fun envelope(body: ByteArray): ReceivedEnvelope? {
        val root = try {
            mapper.readTree(body)
        } catch (e: JacksonException) {
            return null
        }
        if (root == null || !root.isObject) return null
        val eventId = root.get("eventId")?.takeIf { it.isString }?.asString()?.takeIf { ID.matches(it) } ?: return null
        val type = root.get("type")?.takeIf { it.isString }?.asString()?.takeIf { TYPE.matches(it) } ?: return null
        val sequence = root.get("sequence")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()?.takeIf { it >= 1 } ?: return null
        val environment = root.get("environment")?.takeIf { it.isString }?.asString()?.takeIf { ENVIRONMENT.matches(it) } ?: return null
        return ReceivedEnvelope(eventId, type, sequence, environment)
    }

    fun receipts(list: List<MerchantWebhookReceipt>): String {
        val array = nodes.arrayNode()
        list.forEach { r ->
            val o = array.addObject()
            o.put("outcome", r.outcome.name)
            r.envelope?.let { e ->
                o.put("eventId", e.eventId)
                o.put("eventType", e.type)
                o.put("sequence", e.sequence)
                o.put("environment", e.environment)
            }
            r.keyId?.let { o.put("keyId", it) }
            o.put("bodySha256", r.bodySha256)
            o.put("responseStatus", r.responseStatus)
            o.put("receivedAt", TIMESTAMP.format(r.receivedAt))
        }
        return mapper.writeValueAsString(nodes.objectNode().set("receipts", array))
    }
}
