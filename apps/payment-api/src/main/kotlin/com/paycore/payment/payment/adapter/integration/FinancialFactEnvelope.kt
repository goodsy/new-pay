package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.required.FinancialFactRelayEnvelope
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 결제 사실 봉투 v1과 서명(FIN-FACT-RELAY-001 §4.1 — 계약은 golden `payment-fact-relay-v1.golden.json`, FINANCIAL과 코드 공유 없음).
 *
 * - 본문 = compact JSON(UTF-8), 필드 순서 고정: envelopeVersion·eventId·effectKey·eventType·schemaVersion·eventCategory·producer·aggregateType·aggregateId·
 *   aggregateVersion·occurredAt(UTC ms)·payload. payload는 Outbox JSON 열을 읽은 문자열을 해석해 **키 순서를 유지한 채** 다시 쓴다(같은 행은 언제 보내도 같은 바이트 —
 *   FINANCIAL이 같은 eventId 재전송을 원문 SHA-256으로 DUPLICATE 판정한다).
 * - 헤더 `PayCore-Fact-Signature: t=<unix 초>,kid=<keyId>,v1=<hex HMAC-SHA256(key, canonical)>`, canonical = tag LF POST LF 경로 LF t LF hex(SHA-256(본문)).
 */
object FinancialFactEnvelope {
    const val ENVELOPE_VERSION = 1
    const val SIGNATURE_HEADER = "PayCore-Fact-Signature"
    const val CANONICAL_TAG = "paycore.payment-fact.v1"
    const val METHOD = "POST"
    const val PATH = "/internal/v1/payment-facts"
    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val HEX: HexFormat = HexFormat.of()

    fun body(envelope: FinancialFactRelayEnvelope): ByteArray {
        val node = JsonNodeFactory.instance.objectNode()
        node.put("envelopeVersion", ENVELOPE_VERSION)
        node.put("eventId", envelope.eventId)
        node.put("effectKey", envelope.effectKey)
        node.put("eventType", envelope.eventType)
        node.put("schemaVersion", envelope.schemaVersion)
        node.put("eventCategory", envelope.eventCategory)
        node.put("producer", envelope.producer)
        node.put("aggregateType", envelope.aggregateType)
        node.put("aggregateId", envelope.aggregateId)
        node.put("aggregateVersion", envelope.aggregateVersion)
        node.put("occurredAt", TIMESTAMP.format(envelope.occurredAt))
        node.set("payload", mapper.readTree(envelope.payloadJson))
        return mapper.writeValueAsBytes(node)
    }

    fun canonical(t: String, body: ByteArray): String =
        listOf(CANONICAL_TAG, METHOD, PATH, t, HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(body))).joinToString("\n")

    fun signatureHeader(keyId: String, key: ByteArray, t: Long, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(canonical(t.toString(), body).toByteArray(Charsets.UTF_8))
        return "t=$t,kid=$keyId,v1=${HEX.formatHex(mac)}"
    }
}
