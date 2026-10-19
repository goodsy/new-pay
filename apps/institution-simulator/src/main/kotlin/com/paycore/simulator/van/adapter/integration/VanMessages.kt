package com.paycore.simulator.van.adapter.integration

import com.paycore.simulator.van.domain.VanAuthRequest
import com.paycore.simulator.van.domain.VanAuthentication
import com.paycore.simulator.van.domain.VanDecisionOutcome
import com.paycore.simulator.van.domain.VanPlan
import com.paycore.simulator.van.domain.VanPlanKind
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 받은 요청 메시지(엄격 파싱 결과). */
sealed interface VanInbound {
    val messageId: String

    data class Auth(override val messageId: String, val requestedAt: Instant, val request: VanAuthRequest) : VanInbound

    data class Inquiry(
        override val messageId: String,
        val inquiryId: String,
        val originalRequestId: String,
        val institutionAccount: String,
        val environment: String,
        val closeIfAbsent: Boolean,
    ) : VanInbound
}

/**
 * sim-van-1 메시지 JSON(AUTH-001 §5.2, golden `van-contract-sim-van-1.golden.json`). 요청은 엄격 파싱한다: 첫 필드 `type`, 닫힌 필드 집합, 모르는 필드·null·타입 오류·
 * 문법 위반·중복 키·끝 뒤 토큰 → FRAMING. 응답은 계약 필드 순서의 compact JSON으로 쓴다(golden과 바이트 비교). 시각은 UTC ms, 날짜는 ISO.
 */
object VanMessages {
    enum class AuthOutcome { APPROVED, DECLINED, PENDING, REQUEST_CONFLICT, REJECTED_CLOSED }

    enum class InquiryResult { FOUND, PENDING, NOT_FOUND, NO_EFFECT_CONFIRMED, UNAVAILABLE }

    data class Approval(
        val approvalNumber: String,
        val providerTransactionId: String,
        val approvedAmountMinor: Long,
        val currency: String,
        val plan: VanPlan,
        val cardType: String,
        val institutionTransactionDate: LocalDate,
        val institutionBusinessDate: LocalDate,
    )

    private val AUTH_FIELDS = listOf(
        "type", "messageId", "requestId", "institutionAccount", "merchantRef", "environment", "routeRef", "amountMinor", "currency", "plan", "authentication", "requestedAt",
    )
    private val INQUIRY_FIELDS = listOf("type", "messageId", "inquiryId", "originalRequestId", "institutionAccount", "environment", "closeIfAbsent")
    private val AUTHENTICATION_FIELDS = setOf("evidenceRef", "providerCode", "issuerCode", "acquirerCode")

    private val ULID = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
    private val REQUEST_ID = Regex("^ar_[0-9A-HJKMNP-TV-Z]{26}$")
    private val INQUIRY_ID = Regex("^aq_[0-9A-HJKMNP-TV-Z]{26}$")
    private val ACCOUNT = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val MERCHANT_REF = Regex("^[!-~]{1,64}$")
    private val ENVIRONMENT = Regex("^(TEST|LIVE)$")
    private val CURRENCY = Regex("^[A-Z]{3}$")
    private val EVIDENCE_REF = Regex("^[!-~]{1,128}$")
    private val CODE = Regex("^[A-Z0-9-]{1,32}$")
    private val TIMESTAMP_TEXT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun parseRequest(payload: ByteArray): VanInbound {
        if (payload.isEmpty() || payload.size > VanFrameCodec.MAX_LENGTH) throw framing()
        val root = try {
            mapper.readTree(String(payload, Charsets.UTF_8))
        } catch (e: JacksonException) {
            throw framing()
        }
        if (root == null || !root.isObject) throw framing()
        return try {
            when (text(root, "type")) {
                "AUTH_REQUEST" -> parseAuth(root)
                "INQUIRY_REQUEST" -> parseInquiry(root)
                else -> throw framing()
            }
        } catch (e: IllegalArgumentException) {
            throw framing()
        } catch (e: java.time.DateTimeException) {
            throw framing()
        }
    }

    private fun parseAuth(root: JsonNode): VanInbound.Auth {
        if (root.properties().map { it.key } != AUTH_FIELDS) throw framing()
        val plan = root.get("plan")
        if (!plan.isObject) throw framing()
        val planFields = plan.properties().map { it.key }
        if (planFields != listOf("kind") && planFields != listOf("kind", "months")) throw framing()
        val months = plan.get("months")?.let { if (it.isInt) it.intValue() else throw framing() }
        val auth = root.get("authentication")
        if (!auth.isObject || auth.properties().map { it.key }.toSet() != AUTHENTICATION_FIELDS || auth.size() != AUTHENTICATION_FIELDS.size) throw framing()
        val amount = root.get("amountMinor")
        if (!amount.isIntegralNumber || !amount.canConvertToLong() || amount.longValue() < 1) throw framing()
        val requestedAt = text(root, "requestedAt", TIMESTAMP_TEXT)
        return VanInbound.Auth(
            messageId = text(root, "messageId", ULID),
            requestedAt = Instant.parse(requestedAt),
            request = VanAuthRequest(
                requestId = text(root, "requestId", REQUEST_ID),
                institutionAccount = text(root, "institutionAccount", ACCOUNT),
                merchantRef = text(root, "merchantRef", MERCHANT_REF),
                environment = text(root, "environment", ENVIRONMENT),
                routeRef = text(root, "routeRef", ACCOUNT),
                amountMinor = amount.longValue(),
                currency = text(root, "currency", CURRENCY),
                plan = VanPlan(VanPlanKind.valueOf(text(plan, "kind")), months),
                authentication = VanAuthentication(
                    evidenceRef = text(auth, "evidenceRef", EVIDENCE_REF),
                    providerCode = text(auth, "providerCode", CODE),
                    issuerCode = text(auth, "issuerCode", CODE),
                    acquirerCode = text(auth, "acquirerCode", CODE),
                ),
            ),
        )
    }

    private fun parseInquiry(root: JsonNode): VanInbound.Inquiry {
        if (root.properties().map { it.key } != INQUIRY_FIELDS) throw framing()
        val close = root.get("closeIfAbsent")
        if (!close.isBoolean) throw framing()
        return VanInbound.Inquiry(
            messageId = text(root, "messageId", ULID),
            inquiryId = text(root, "inquiryId", INQUIRY_ID),
            originalRequestId = text(root, "originalRequestId", REQUEST_ID),
            institutionAccount = text(root, "institutionAccount", ACCOUNT),
            environment = text(root, "environment", ENVIRONMENT),
            closeIfAbsent = close.booleanValue(),
        )
    }

    @Suppress("LongParameterList")
    fun authResponse(
        inReplyTo: String, requestId: String, outcome: AuthOutcome, decisionId: String?, pendingId: String?, approval: Approval?, declineCategory: String?,
        respondedAt: Instant,
    ): ByteArray {
        require((outcome == AuthOutcome.APPROVED || outcome == AuthOutcome.DECLINED) == (decisionId != null)) { "a decision id exists exactly for decisions" }
        require((outcome == AuthOutcome.PENDING) == (pendingId != null)) { "a pending id exists exactly for PENDING" }
        require((outcome == AuthOutcome.APPROVED) == (approval != null)) { "approval terms exist exactly for APPROVED" }
        require((outcome == AuthOutcome.DECLINED) == (declineCategory != null)) { "a decline category exists exactly for DECLINED" }
        val o = mapper.createObjectNode()
        o.put("type", "AUTH_RESPONSE").put("inReplyTo", inReplyTo).put("requestId", requestId).put("outcome", outcome.name)
        decisionId?.let { o.put("decisionId", it) }
        pendingId?.let { o.put("pendingId", it) }
        approval?.let { putApproval(o, it) }
        declineCategory?.let { o.put("declineCategory", it) }
        o.put("respondedAt", TIMESTAMP.format(respondedAt))
        return mapper.writeValueAsBytes(o)
    }

    @Suppress("LongParameterList")
    fun inquiryResponse(
        inReplyTo: String, inquiryId: String, originalRequestId: String, result: InquiryResult, pendingId: String?, decisionId: String?, outcome: VanDecisionOutcome?,
        approval: Approval?, declineCategory: String?, closureId: String?, respondedAt: Instant,
    ): ByteArray {
        require((result == InquiryResult.FOUND) == (decisionId != null && outcome != null)) { "a decision exists exactly for FOUND" }
        require((result == InquiryResult.PENDING) == (pendingId != null)) { "a pending id exists exactly for PENDING" }
        require((result == InquiryResult.NO_EFFECT_CONFIRMED) == (closureId != null)) { "a closure id exists exactly for NO_EFFECT_CONFIRMED" }
        require((outcome == VanDecisionOutcome.APPROVED) == (approval != null) && (outcome == VanDecisionOutcome.DECLINED) == (declineCategory != null)) {
            "decision fields follow the outcome"
        }
        val o = mapper.createObjectNode()
        o.put("type", "INQUIRY_RESPONSE").put("inReplyTo", inReplyTo).put("inquiryId", inquiryId).put("originalRequestId", originalRequestId).put("result", result.name)
        pendingId?.let { o.put("pendingId", it) }
        decisionId?.let { o.put("decisionId", it) }
        outcome?.let { o.put("outcome", it.name) }
        approval?.let { putApproval(o, it) }
        declineCategory?.let { o.put("declineCategory", it) }
        closureId?.let { o.put("closureId", it) }
        o.put("respondedAt", TIMESTAMP.format(respondedAt))
        return mapper.writeValueAsBytes(o)
    }

    private fun putApproval(o: ObjectNode, a: Approval) {
        o.put("approvalNumber", a.approvalNumber).put("providerTransactionId", a.providerTransactionId).put("approvedAmountMinor", a.approvedAmountMinor)
            .put("currency", a.currency)
        val plan = o.putObject("plan").put("kind", a.plan.kind.name)
        a.plan.months?.let { plan.put("months", it) }
        o.put("cardType", a.cardType).put("institutionTransactionDate", a.institutionTransactionDate.toString())
            .put("institutionBusinessDate", a.institutionBusinessDate.toString())
    }

    private fun text(node: JsonNode, field: String, pattern: Regex? = null): String {
        val v = node.get(field)
        if (v == null || !v.isString) throw framing()
        val s = v.stringValue()
        if (pattern != null && !pattern.matches(s)) throw framing()
        return s
    }

    private fun framing() = VanFrameException(VanFrameFailure.FRAMING)
}
