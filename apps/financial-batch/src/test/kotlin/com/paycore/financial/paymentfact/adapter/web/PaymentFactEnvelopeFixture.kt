package com.paycore.financial.paymentfact.adapter.web

import com.paycore.financial.paymentfact.application.provided.InboundPaymentFact
import com.paycore.financial.paymentfact.domain.AuthorizedPaymentFact
import com.paycore.financial.paymentfact.domain.CancelledPaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFact
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 시험용 봉투 v1 본문(golden `payment-fact-relay-v1.golden.json`의 필드 이름·형식 — PAYMENT `OutboxPayloadJson`과 같은 이름). DB·HTTP 시험이 domain Fixture 사실을
 * 실제 수신 경로(파서 → Use Case)로 넣을 때 쓴다. 서명은 golden 시험 키로 만든다(운영 키 아님).
 */
object PaymentFactEnvelopeFixture {
    private val mapper = JsonMapper.builder().build()
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    const val KID = "fact-golden-k1"
    val KEY: ByteArray = Base64.getDecoder().decode("dGVzdC1vbmx5LXBheW1lbnQtZmFjdC1nb2xkZW4ta2V5LTAwMDE=")

    fun body(fact: PaymentFact, eventId: String = UUID.randomUUID().toString(), mutate: (ObjectNode) -> Unit = {}): ByteArray {
        val node = JsonNodeFactory.instance.objectNode()
        node.put("envelopeVersion", 1)
        node.put("eventId", eventId)
        node.put("effectKey", fact.effectKey)
        node.put("eventType", fact.kind.eventType)
        node.put("schemaVersion", 1)
        node.put("eventCategory", "FINANCIAL_FACT")
        node.put("producer", "payment-api/payment")
        when (fact) {
            is AuthorizedPaymentFact -> {
                node.put("aggregateType", "PaymentAuthorization")
                node.put("aggregateId", fact.authorizationId)
            }
            is CancelledPaymentFact -> {
                node.put("aggregateType", "PaymentCancellation")
                node.put("aggregateId", fact.cancellationId)
            }
        }
        node.put("aggregateVersion", 1)
        node.put("occurredAt", TIMESTAMP.format(Instant.parse("2026-11-05T01:02:03.456Z")))
        node.set("payload", payload(fact))
        mutate(node)
        return mapper.writeValueAsBytes(node)
    }

    fun inbound(fact: PaymentFact, eventId: String = UUID.randomUUID().toString(), mutate: (ObjectNode) -> Unit = {}): InboundPaymentFact =
        PaymentFactEnvelopeParser.parse(body(fact, eventId, mutate), KID)

    fun unsupportedBody(eventId: String = UUID.randomUUID().toString(), effectKey: String = "PaymentAuthorized:ptx_future_$eventId"): ByteArray {
        val node = JsonNodeFactory.instance.objectNode()
        node.put("envelopeVersion", 1)
        node.put("eventId", eventId)
        node.put("effectKey", effectKey)
        node.put("eventType", "PaymentAuthorized")
        node.put("schemaVersion", 2)
        node.put("eventCategory", "FINANCIAL_FACT")
        node.put("producer", "payment-api/payment")
        node.put("aggregateType", "PaymentAuthorization")
        node.put("aggregateId", "pau_future")
        node.put("aggregateVersion", 1)
        node.put("occurredAt", "2026-11-05T01:02:03.456Z")
        node.putObject("payload").put("note", "future schema")
        return mapper.writeValueAsBytes(node)
    }

    /** golden 서명 규칙(t = 지금 초). */
    fun signature(body: ByteArray, t: Long = Instant.now().epochSecond, kid: String = KID, key: ByteArray = KEY): String {
        val canonical = PaymentFactSignature.canonical(t.toString(), body)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return "t=$t,kid=$kid,v1=${java.util.HexFormat.of().formatHex(mac)}"
    }

    private fun payload(fact: PaymentFact): ObjectNode {
        val p = JsonNodeFactory.instance.objectNode()
        val i = fact.identity
        p.put("environment", i.environment.name)
        p.put("merchantAccountId", i.merchantAccountId)
        p.put("merchantSiteId", i.merchantSiteId)
        p.put("merchantOrderId", i.merchantOrderId)
        p.put("paymentId", i.paymentId)
        p.put("authorizationId", fact.authorizationId)
        when (fact) {
            is AuthorizedPaymentFact -> {
                p.put("transactionId", fact.transactionId)
                p.put("amount", fact.money.minor)
                p.put("currency", fact.money.currency)
                p.putObject("plan").also { o -> o.put("kind", fact.plan.kind); fact.plan.installmentMonths?.let { o.put("months", it) } }
                p.put("cardType", fact.cardType)
                p.put("issuerCode", fact.issuerCode)
                p.put("acquirerCode", fact.acquirerCode)
                p.put("approvalNumber", fact.approvalNumber)
                p.put("providerTransactionId", fact.providerTransactionId)
                p.put("institutionTransactionDate", fact.institutionTransactionDate.toString())
                fact.institutionBusinessDate?.let { p.put("institutionBusinessDate", it.toString()) } ?: p.putNull("institutionBusinessDate")
                p.put("approvedAt", TIMESTAMP.format(fact.approvedAt))
            }
            is CancelledPaymentFact -> {
                p.put("originalTransactionId", fact.originalTransactionId)
                p.put("cancellationId", fact.cancellationId)
                p.put("transactionId", fact.transactionId)
                p.put("amount", fact.money.minor)
                p.put("currency", fact.money.currency)
                p.put("scope", fact.scope)
                p.put("authorizedAmount", fact.authorizedAmountMinor)
                p.put("returnSequence", fact.returnSequence)
                p.put("cumulativeReturnedAmount", fact.cumulativeReturnedMinor)
                p.put("returnStatusAfter", fact.returnStatusAfter)
                fact.cancelApprovalNumber?.let { p.put("cancelApprovalNumber", it) } ?: p.putNull("cancelApprovalNumber")
                p.put("providerTransactionId", fact.providerTransactionId)
                p.put("originalProviderTransactionId", fact.originalProviderTransactionId)
                p.put("institutionTransactionDate", fact.institutionTransactionDate.toString())
                fact.institutionBusinessDate?.let { p.put("institutionBusinessDate", it.toString()) } ?: p.putNull("institutionBusinessDate")
                p.put("cancelledAt", TIMESTAMP.format(fact.cancelledAt))
                p.put("initiatorKind", fact.initiatorKind)
                p.putObject("originalPlan").also { o -> o.put("kind", fact.originalPlan.kind); fact.originalPlan.installmentMonths?.let { o.put("months", it) } }
                p.put("originalCardType", fact.originalCardType)
            }
        }
        p.put("policyAssignmentVersion", fact.policyAssignmentVersion)
        p.put("entitlementVersion", fact.entitlementVersion)
        return p
    }
}
