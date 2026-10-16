package com.paycore.payment.entitlement.adapter.web

import com.paycore.payment.entitlement.application.provided.InboundSnapshotEnvelope
import com.paycore.payment.entitlement.application.provided.ReceiveEntitlementSnapshotUseCase
import com.paycore.payment.entitlement.application.provided.ReceiveFailure
import com.paycore.payment.entitlement.application.provided.ReceiveResult
import com.paycore.payment.entitlement.domain.EntitlementContent
import com.paycore.payment.entitlement.domain.IncomingSnapshot
import com.paycore.payment.entitlement.domain.JsonValue
import com.paycore.payment.entitlement.domain.isSupportedSnapshotSchema
import com.paycore.payment.entitlement.domain.SnapshotContractViolation
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * MASTER → PAYMENT 권리 Snapshot 내부 Inbox(push relay 수신, 명세 §7). 외부 공개 경로와 분리된 `/internal/v1/…`(외부 노출 차단 구성은 CP-API-001).
 *
 * 순서(TX 밖 검증 → use case의 로컬 TX → 커밋 뒤 응답):
 * 1. 서명 검증 실패·키 없음 → 401(저장 없음 — MASTER는 격리)
 * 2. 봉투 해석 불가 → 400, 내용 계약 위반(구조·hash 불일치·봉투-내용 불일치) → 422(저장 없음)
 * 3. use case → 200 `{consumer, eventId, outcome, appliedRevision[, originalOutcome — DUPLICATE일 때 처음 저장한 outcome]}`(내구 커밋 뒤 ACK) / 같은 eventId·다른 본문 409 / 일시 실패·결과 불명확 503
 * 로그에 본문·서명·키를 남기지 않는다.
 */
@RestController
@RequestMapping("/internal/v1/entitlement-snapshots")
class EntitlementInboxController(
    private val receive: ReceiveEntitlementSnapshotUseCase,
    private val settings: EntitlementInboxSettings,
    private val registry: MeterRegistry,
) {
    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val log = LoggerFactory.getLogger(EntitlementInboxController::class.java)

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun receive(
        @RequestBody body: ByteArray,
        @RequestHeader(name = EntitlementSignature.HEADER, required = false) signature: String?,
    ): ResponseEntity<Map<String, Any>> {
        if (!EntitlementSignature.verify(settings, signature, body)) return reject(HttpStatus.UNAUTHORIZED, "SIGNATURE_INVALID")
        val envelope = try {
            parse(body)
        } catch (e: EnvelopeInvalid) {
            return reject(HttpStatus.BAD_REQUEST, "ENVELOPE_INVALID")
        } catch (e: SnapshotContractViolation) {
            return reject(HttpStatus.UNPROCESSABLE_CONTENT, "CONTENT_INVALID")
        }
        return try {
            when (val result = receive.receive(envelope)) {
                is ReceiveResult.Stored -> ResponseEntity.ok(
                    mapOf(
                        "consumer" to result.consumer, "eventId" to envelope.snapshot.eventId, "outcome" to result.outcome.name,
                        "appliedRevision" to result.appliedRevision,
                    ) + (result.originalOutcome?.let { mapOf("originalOutcome" to it.name) } ?: emptyMap()),
                )
                ReceiveResult.EventIdConflict -> reject(HttpStatus.CONFLICT, "EVENT_ID_CONFLICT")
            }
        } catch (e: ReceiveFailure) {
            // n-3: 예외 메시지 원문 대신 분류(예외 클래스 이름 사슬)만 남긴다.
            log.warn(
                "operation=ReceiveEntitlementSnapshot eventId={} result=TEMPORARILY_UNAVAILABLE failure={}",
                envelope.snapshot.eventId, generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSE_DEPTH).joinToString(" <- ") { it.javaClass.simpleName },
            )
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to "TEMPORARILY_UNAVAILABLE"))
        }
    }

    private fun reject(status: HttpStatus, kind: String): ResponseEntity<Map<String, Any>> {
        registry.counter(REJECTED, "kind", kind).increment()
        log.warn("entitlement snapshot rejected status={} kind={}", status.value(), kind)
        return ResponseEntity.status(status).body(mapOf("error" to kind))
    }

    private fun parse(body: ByteArray): InboundSnapshotEnvelope {
        val node = try {
            mapper.readTree(body)
        } catch (e: JacksonException) {
            throw EnvelopeInvalid()
        }
        if (node == null || !node.isObject) throw EnvelopeInvalid()
        if (node.int("envelopeVersion") != ENVELOPE_VERSION) throw EnvelopeInvalid()
        val eventId = node.text("eventId").takeIf { UUID.matches(it) } ?: throw EnvelopeInvalid()
        val eventType = node.text("eventType").takeIf { it == EVENT_TYPE } ?: throw EnvelopeInvalid()
        val schemaVersion = node.int("schemaVersion").takeIf { it >= 1 } ?: throw EnvelopeInvalid()
        val merchantId = node.text("aggregateId").takeIf { it.isNotEmpty() && it.length <= MAX_ID } ?: throw EnvelopeInvalid()
        val revision = node.get("aggregateVersion")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()?.takeIf { it >= 1 }
            ?: throw EnvelopeInvalid()
        val payload = node.get("payload")?.takeIf { it.isObject } ?: throw EnvelopeInvalid()
        val snapshot = if (isSupportedSnapshotSchema(schemaVersion)) {
            val tree = WebJsonTree.toJsonValue(payload)
            IncomingSnapshot(
                eventId = eventId, merchantId = merchantId, revision = revision, schemaVersion = schemaVersion,
                content = EntitlementContent.fromJson(tree, schemaVersion, allowLeaseFields = true),
                declaredContentHash = (tree.obj("snapshot").fields["contentHash"] as? JsonValue.JString)?.value
                    ?: throw SnapshotContractViolation("contentHash must be a string"),
                lease = EntitlementContent.leaseFromJson(tree),
            )
        } else {
            IncomingSnapshot(eventId, merchantId, revision, schemaVersion, null, null, null)
        }
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))
        return InboundSnapshotEnvelope(eventType, snapshot, String(body, StandardCharsets.UTF_8), sha)
    }

    private fun JsonNode.text(name: String): String = get(name)?.takeIf { it.isString }?.stringValue() ?: throw EnvelopeInvalid()

    private fun JsonNode.int(name: String): Int = get(name)?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue() ?: throw EnvelopeInvalid()

    private class EnvelopeInvalid : RuntimeException(null, null, false, false)

    companion object {
        const val ENVELOPE_VERSION = 1
        const val EVENT_TYPE = "EntitlementSnapshot"
        const val REJECTED = "paycore.payment.entitlement.inbox.rejected"
        private const val MAX_ID = 64
        private const val MAX_CAUSE_DEPTH = 10
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}

/** Jackson 노드 → domain [JsonValue](web adapter용 — adapter 간 의존을 두지 않으려 persistence의 같은 변환을 공유하지 않는다). */
internal object WebJsonTree {
    fun toJsonValue(node: JsonNode): JsonValue = when {
        node.isNull -> JsonValue.JNull
        node.isObject -> JsonValue.JObject(node.properties().associate { (k, v) -> k to toJsonValue(v) })
        node.isArray -> JsonValue.JArray((0 until node.size()).map { toJsonValue(node.get(it)) })
        node.isString -> JsonValue.JString(node.stringValue())
        node.isBoolean -> JsonValue.JBool(node.booleanValue())
        node.isIntegralNumber && node.canConvertToLong() -> JsonValue.JNumber(node.longValue())
        else -> throw SnapshotContractViolation("unsupported JSON value")
    }
}
