package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.application.required.RelayEnvelope
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 권리 Snapshot 봉투 v1과 서명(D-MS-004: 봉투 HMAC-SHA256, 환경별 키, 교체용 키 2개 동시 허용은 수신 측). 같은 규칙을 PAYMENT가 검증한다
 * (계약 golden — MASTER-SNAPSHOT-001 S7).
 *
 * - 본문 = 봉투 JSON(UTF-8): `envelopeVersion`·`eventId`·`eventType`·`schemaVersion`·`producer`·`aggregateId`(merchantId)·`aggregateVersion`(revision)·
 *   `occurredAt`(UTC ms)·`payload`(Snapshot 전송 payload 객체).
 * - 헤더 `PayCore-Signature: keyId=<id>,v1=<HMAC-SHA256(key, 본문 바이트) 소문자 hex>`. 서명 대상은 전송한 본문 바이트 그대로다.
 */
object EntitlementEnvelope {
    const val ENVELOPE_VERSION = 1
    const val SIGNATURE_HEADER = "PayCore-Signature"
    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun body(envelope: RelayEnvelope): ByteArray {
        val node = JsonNodeFactory.instance.objectNode()
        node.put("envelopeVersion", ENVELOPE_VERSION)
        node.put("eventId", envelope.eventId)
        node.put("eventType", envelope.eventType)
        node.put("schemaVersion", envelope.schemaVersion)
        node.put("producer", envelope.producer)
        node.put("aggregateId", envelope.aggregateId)
        node.put("aggregateVersion", envelope.aggregateVersion)
        node.put("occurredAt", TIMESTAMP.format(envelope.occurredAt))
        node.set("payload", mapper.readTree(envelope.payloadJson))
        return mapper.writeValueAsBytes(node)
    }

    fun signatureHeader(keyId: String, key: ByteArray, body: ByteArray): String = "keyId=$keyId,v1=${hmacHex(key, body)}"

    fun hmacHex(key: ByteArray, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return java.util.HexFormat.of().formatHex(mac.doFinal(body))
    }
}
