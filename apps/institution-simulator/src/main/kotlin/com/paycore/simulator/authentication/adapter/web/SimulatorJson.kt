package com.paycore.simulator.authentication.adapter.web

import com.paycore.simulator.authentication.application.provided.Registration
import com.paycore.simulator.authentication.domain.LookupResult
import com.paycore.simulator.authentication.domain.PlanEcho
import com.paycore.simulator.authentication.domain.RegistrationRequest
import com.paycore.simulator.authentication.domain.RejectionReason
import com.paycore.simulator.authentication.domain.SimulatorRejection
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 계약 v1 JSON(AUTHN-001 §13.4, golden `authentication-contract-v1.golden.json`). 요청은 엄격 파싱(닫힌 필드 집합·타입·null 금지·중복 키·끝 뒤 토큰 거절 →
 * INVALID_REQUEST), 응답은 계약 필드 순서로 쓴다. 조회 응답의 증거 6필드는 AUTHENTICATED에만 싣는다(m-E — 다른 outcome은 키 부재). 시각은 UTC ms.
 */
internal object SimulatorJson {
    const val MAX_BODY_BYTES = 16 * 1024

    private val REQUEST_FIELDS = setOf("paymentId", "merchantRef", "environment", "amountMinor", "currency", "plan", "cardCompany", "state", "returnUrl")
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private val nodes = JsonNodeFactory.instance

    fun parseRegistration(body: ByteArray): RegistrationRequest {
        if (body.isEmpty() || body.size > MAX_BODY_BYTES) throw invalid()
        val root = try {
            mapper.readTree(String(body, Charsets.UTF_8))
        } catch (e: JacksonException) {
            throw invalid()
        }
        if (root == null || !root.isObject || root.properties().map { it.key }.toSet() != REQUEST_FIELDS) throw invalid()
        val plan = root.get("plan")
        if (!plan.isObject) throw invalid()
        val planFields = plan.properties().map { it.key }.toSet()
        if (planFields != setOf("kind") && planFields != setOf("kind", "months")) throw invalid()
        val months = plan.get("months")?.let { if (it.isInt) it.intValue() else throw invalid() }
        val amount = root.get("amountMinor")
        if (!amount.isIntegralNumber || !amount.canConvertToLong()) throw invalid()
        return RegistrationRequest(
            paymentId = text(root, "paymentId"),
            merchantRef = text(root, "merchantRef"),
            environment = text(root, "environment"),
            amountMinor = amount.longValue(),
            currency = text(root, "currency"),
            plan = PlanEcho(text(plan, "kind"), months),
            cardCompany = text(root, "cardCompany"),
            state = text(root, "state"),
            returnUrl = text(root, "returnUrl"),
        )
    }

    fun registration(r: Registration): String = nodes.objectNode().also {
        it.put("authenticationTransactionId", r.transactionId.value)
        it.put("customerUrl", r.customerUrl)
        it.put("expiresAt", TIMESTAMP.format(r.expiresAt))
    }.toString()

    fun lookup(r: LookupResult): String = nodes.objectNode().also { o ->
        o.put("resultRef", r.resultRef.value)
        o.put("authenticationTransactionId", r.transactionId.value)
        o.put("outcome", r.outcome.name)
        o.put("state", r.state)
        val b: ObjectNode = o.putObject("binding")
        b.put("paymentId", r.binding.paymentId)
        b.put("merchantRef", r.binding.merchantRef)
        b.put("environment", r.binding.environment)
        b.put("amountMinor", r.binding.amountMinor)
        b.put("currency", r.binding.currency)
        val p = b.putObject("plan")
        p.put("kind", r.binding.plan.kind)
        r.binding.plan.months?.let { p.put("months", it) }
        b.put("cardCompany", r.binding.cardCompany)
        if (r.authenticatedAt != null) {
            o.put("cardType", r.cardType)
            o.put("issuerCode", r.issuerCode)
            o.put("acquirerCode", r.acquirerCode)
            o.put("providerCode", r.providerCode)
            o.put("evidenceRef", r.evidenceRef)
            o.put("authenticatedAt", TIMESTAMP.format(r.authenticatedAt))
        }
        o.put("validUntil", TIMESTAMP.format(r.validUntil))
    }.toString()

    fun error(reason: RejectionReason): String = nodes.objectNode().put("code", reason.name).toString()

    fun timestamp(at: Instant): String = TIMESTAMP.format(at)

    private fun text(node: JsonNode, field: String): String {
        val v = node.get(field)
        if (v == null || !v.isString) throw invalid()
        return v.stringValue()
    }

    private fun invalid() = SimulatorRejection(RejectionReason.INVALID_REQUEST)
}
