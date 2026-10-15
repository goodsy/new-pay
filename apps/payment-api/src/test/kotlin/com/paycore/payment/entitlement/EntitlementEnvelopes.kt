package com.paycore.payment.entitlement

import com.paycore.payment.entitlement.adapter.web.EntitlementSignature
import com.paycore.payment.entitlement.domain.CanonicalJson
import com.paycore.payment.entitlement.domain.EntitlementContent
import com.paycore.payment.entitlement.domain.Lease
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * 시험용 봉투 v1 작성기·서명기(MASTER Relay가 보내는 모양과 같은 계약 — 계약 일치 자체는 golden 시험이 양쪽에서 확인한다). 키는 시험 전용 값이다.
 */
object EntitlementEnvelopes {
    val T0: Instant = Instant.parse("2026-10-09T02:00:00.000Z")
    const val KEY_ID = "test-k1"
    const val ROTATION_KEY_ID = "test-k2"
    val KEY: ByteArray = Base64.getDecoder().decode("dGVzdC1vbmx5LWVudGl0bGVtZW50LXNpZ25pbmcta2V5LTEh")
    val ROTATION_KEY: ByteArray = Base64.getDecoder().decode("dGVzdC1vbmx5LWVudGl0bGVtZW50LXNpZ25pbmcta2V5LTIh")
    private val mapper = JsonMapper.builder().build()

    /** 지원 버전 봉투 본문(payload = 정규 내용 + contentHash + lease). [contentHash]로 위조 hash를 넣을 수 있다. */
    fun body(content: EntitlementContent, lease: Lease, eventId: String = UUID.randomUUID().toString(), contentHash: String = content.contentHash): ByteArray {
        val payload = CanonicalJson.write(
            mapper.readValue(content.canonical, Map::class.java).entries.associate { (k, v) -> k as String to v } +
                mapOf("contentHash" to contentHash, "issuedAt" to lease.issuedAt, "leaseSeq" to lease.leaseSeq, "leaseUntil" to lease.leaseUntil),
        )
        return envelope(eventId, content.schemaVersion, content.merchantId, content.revision, payload)
    }

    fun envelope(eventId: String, schemaVersion: Int, merchantId: String, revision: Long, payloadJson: String): ByteArray =
        (
            """{"envelopeVersion":1,"eventId":"$eventId","eventType":"EntitlementSnapshot","schemaVersion":$schemaVersion,"producer":"master-api",""" +
                """"aggregateId":"$merchantId","aggregateVersion":$revision,"occurredAt":"${CanonicalJson.timestamp(T0)}","payload":$payloadJson}"""
            ).toByteArray()

    fun signature(body: ByteArray, keyId: String = KEY_ID, key: ByteArray = KEY): String =
        "keyId=$keyId,v1=" + HexFormat.of().formatHex(EntitlementSignature.hmac(key, body))

    fun eventIdOf(body: ByteArray): String = mapper.readTree(body).get("eventId").stringValue()

    data class Response(val status: Int, val json: JsonNode?)

    fun send(mvc: MockMvc, body: ByteArray, signature: String? = signature(body)): Response {
        val request = post("/internal/v1/entitlement-snapshots").contentType(MediaType.APPLICATION_JSON).content(body)
        signature?.let { request.header(EntitlementSignature.HEADER, it) }
        val result: MvcResult = mvc.perform(request).andReturn()
        val text = result.response.contentAsString
        return Response(result.response.status, text.takeIf { it.isNotEmpty() }?.let { mapper.readTree(it) })
    }
}
