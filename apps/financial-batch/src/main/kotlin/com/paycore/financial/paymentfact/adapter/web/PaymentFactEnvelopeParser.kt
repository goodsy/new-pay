package com.paycore.financial.paymentfact.adapter.web

import com.paycore.financial.paymentfact.application.provided.InboundPaymentFact
import com.paycore.financial.paymentfact.domain.AuthorizedPaymentFact
import com.paycore.financial.paymentfact.domain.CancelledPaymentFact
import com.paycore.financial.paymentfact.domain.FactEnvironment
import com.paycore.financial.paymentfact.domain.FactMoney
import com.paycore.financial.paymentfact.domain.FactPlan
import com.paycore.financial.paymentfact.domain.PaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFactContractViolation
import com.paycore.financial.paymentfact.domain.PaymentFactIdentity
import com.paycore.financial.paymentfact.domain.PaymentFactKind
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.HexFormat

/**
 * 결제 사실 봉투 v1 해석(FIN-FACT-RELAY-001 §4.1 — 서명 검증 뒤, TX 밖). 형식은 golden `payment-fact-relay-v1.golden.json`이 고정한다.
 *
 * - 봉투 오류([EnvelopeInvalid] → 400): JSON 아님·중복 키·뒤 토큰, envelopeVersion ≠ 1, 필수 필드 누락·타입 불일치, eventId가 소문자 UUID 아님, eventCategory ≠ FINANCIAL_FACT.
 * - 내용 계약 위반([PaymentFactContractViolation] → 422): 지원 사실의 payload 필드 누락·형식, effectKey ≠ `<eventType>:<transactionId>`, aggregate가 사실과 다름.
 * - 지원하지 않는 (eventType, schemaVersion): payload를 해석하지 않고 fact = null(UNSUPPORTED로 원문 보존).
 */
internal object PaymentFactEnvelopeParser {
    const val ENVELOPE_VERSION = 1
    const val FINANCIAL_FACT = "FINANCIAL_FACT"

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun parse(body: ByteArray, signingKeyId: String): InboundPaymentFact {
        val node = try {
            mapper.readTree(body)
        } catch (e: JacksonException) {
            throw EnvelopeInvalid()
        }
        if (node == null || !node.isObject) throw EnvelopeInvalid()
        if (node.envelopeInt("envelopeVersion") != ENVELOPE_VERSION) throw EnvelopeInvalid()
        val eventId = node.envelopeText("eventId").takeIf { UUID.matches(it) } ?: throw EnvelopeInvalid()
        val effectKey = node.envelopeText("effectKey").takeIf { it.isNotEmpty() && it.length <= MAX_EFFECT_KEY } ?: throw EnvelopeInvalid()
        val eventType = node.envelopeText("eventType").takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: throw EnvelopeInvalid()
        val schemaVersion = node.envelopeInt("schemaVersion").takeIf { it >= 1 } ?: throw EnvelopeInvalid()
        if (node.envelopeText("eventCategory") != FINANCIAL_FACT) throw EnvelopeInvalid()
        val producer = node.envelopeText("producer").takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: throw EnvelopeInvalid()
        val aggregateType = node.envelopeText("aggregateType").takeIf { it.isNotEmpty() && it.length <= MAX_AGGREGATE_TYPE } ?: throw EnvelopeInvalid()
        val aggregateId = node.envelopeText("aggregateId").takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: throw EnvelopeInvalid()
        val aggregateVersion = node.get("aggregateVersion")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()?.takeIf { it >= 0 }
            ?: throw EnvelopeInvalid()
        val occurredAt = try {
            Instant.parse(node.envelopeText("occurredAt"))
        } catch (e: DateTimeParseException) {
            throw EnvelopeInvalid()
        }
        val payload = node.get("payload")?.takeIf { it.isObject } ?: throw EnvelopeInvalid()

        val fact = PaymentFactKind.supported(eventType, schemaVersion)?.let { kind ->
            val f = when (kind) {
                PaymentFactKind.AUTHORIZATION -> authorized(payload)
                PaymentFactKind.CANCELLATION -> cancelled(payload)
            }
            if (f.effectKey != effectKey) throw PaymentFactContractViolation("effectKey must be <eventType>:<transactionId>")
            val expectedAggregate = when (f) {
                is AuthorizedPaymentFact -> AUTHORIZATION_AGGREGATE to f.authorizationId
                is CancelledPaymentFact -> CANCELLATION_AGGREGATE to f.cancellationId
            }
            if (aggregateType to aggregateId != expectedAggregate) throw PaymentFactContractViolation("aggregate must be the fact's authorization or cancellation")
            f
        }
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))
        return InboundPaymentFact(
            eventId = eventId, effectKey = effectKey, eventType = eventType, schemaVersion = schemaVersion, producer = producer, aggregateType = aggregateType,
            aggregateId = aggregateId, aggregateVersion = aggregateVersion, occurredAt = occurredAt, fact = fact,
            rawBody = String(body, StandardCharsets.UTF_8), rawSha256 = sha, signingKeyId = signingKeyId,
        )
    }

    private fun authorized(p: JsonNode): PaymentFact = AuthorizedPaymentFact(
        identity = identity(p), authorizationId = p.text("authorizationId"), transactionId = p.text("transactionId"),
        money = FactMoney(p.long("amount"), p.text("currency")), plan = plan(p.obj("plan")), cardType = p.text("cardType"), issuerCode = p.text("issuerCode"),
        acquirerCode = p.text("acquirerCode"), approvalNumber = p.text("approvalNumber"), providerTransactionId = p.text("providerTransactionId"),
        institutionTransactionDate = p.date("institutionTransactionDate"), institutionBusinessDate = p.optionalDate("institutionBusinessDate"),
        approvedAt = p.instant("approvedAt"), policyAssignmentVersion = p.text("policyAssignmentVersion"), entitlementVersion = p.text("entitlementVersion"),
    )

    private fun cancelled(p: JsonNode): PaymentFact = CancelledPaymentFact(
        identity = identity(p), authorizationId = p.text("authorizationId"), originalTransactionId = p.text("originalTransactionId"),
        cancellationId = p.text("cancellationId"), transactionId = p.text("transactionId"), money = FactMoney(p.long("amount"), p.text("currency")),
        scope = p.text("scope"), authorizedAmountMinor = p.long("authorizedAmount"), returnSequence = p.int("returnSequence"),
        cumulativeReturnedMinor = p.long("cumulativeReturnedAmount"), returnStatusAfter = p.text("returnStatusAfter"),
        cancelApprovalNumber = p.optionalText("cancelApprovalNumber"), providerTransactionId = p.text("providerTransactionId"),
        originalProviderTransactionId = p.text("originalProviderTransactionId"), institutionTransactionDate = p.date("institutionTransactionDate"),
        institutionBusinessDate = p.optionalDate("institutionBusinessDate"), cancelledAt = p.instant("cancelledAt"),
        policyAssignmentVersion = p.text("policyAssignmentVersion"), entitlementVersion = p.text("entitlementVersion"), initiatorKind = p.text("initiatorKind"),
        originalPlan = plan(p.obj("originalPlan")), originalCardType = p.text("originalCardType"),
    )

    private fun identity(p: JsonNode) = PaymentFactIdentity(
        environment = p.text("environment").let { env -> FactEnvironment.entries.firstOrNull { it.name == env } }
            ?: throw PaymentFactContractViolation("environment must be TEST or LIVE"),
        merchantAccountId = p.text("merchantAccountId"), merchantSiteId = p.text("merchantSiteId"), merchantOrderId = p.text("merchantOrderId"),
        paymentId = p.text("paymentId"),
    )

    private fun plan(p: JsonNode): FactPlan {
        val months = p.get("months")?.takeUnless { it.isNull }?.let { m ->
            m.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue() ?: throw PaymentFactContractViolation("plan months must be an integer")
        }
        return FactPlan(p.text("kind"), months)
    }

    private fun JsonNode.envelopeText(name: String): String = get(name)?.takeIf { it.isString }?.stringValue() ?: throw EnvelopeInvalid()

    private fun JsonNode.envelopeInt(name: String): Int = get(name)?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue() ?: throw EnvelopeInvalid()

    private fun JsonNode.text(name: String): String =
        get(name)?.takeIf { it.isString }?.stringValue() ?: throw PaymentFactContractViolation("$name must be a string")

    private fun JsonNode.optionalText(name: String): String? {
        val n = get(name) ?: return null
        if (n.isNull) return null
        if (!n.isString) throw PaymentFactContractViolation("$name must be a string or null")
        return n.stringValue()
    }

    private fun JsonNode.long(name: String): Long =
        get(name)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue() ?: throw PaymentFactContractViolation("$name must be an integer")

    private fun JsonNode.int(name: String): Int =
        get(name)?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue() ?: throw PaymentFactContractViolation("$name must be an integer")

    private fun JsonNode.obj(name: String): JsonNode = get(name)?.takeIf { it.isObject } ?: throw PaymentFactContractViolation("$name must be an object")

    private fun JsonNode.date(name: String): LocalDate = try {
        LocalDate.parse(text(name))
    } catch (e: DateTimeParseException) {
        throw PaymentFactContractViolation("$name must be an ISO date")
    }

    private fun JsonNode.optionalDate(name: String): LocalDate? = if (get(name) == null || get(name).isNull) null else date(name)

    private fun JsonNode.instant(name: String): Instant = try {
        Instant.parse(text(name))
    } catch (e: DateTimeParseException) {
        throw PaymentFactContractViolation("$name must be an ISO-8601 UTC instant")
    }

    /** 봉투 형식 오류(400). 받은 값을 담지 않는다. */
    class EnvelopeInvalid : RuntimeException(null, null, false, false)

    private const val MAX_EFFECT_KEY = 128
    private const val MAX_NAME = 64
    private const val MAX_AGGREGATE_TYPE = 32
    private const val AUTHORIZATION_AGGREGATE = "PaymentAuthorization"
    private const val CANCELLATION_AGGREGATE = "PaymentCancellation"
}
