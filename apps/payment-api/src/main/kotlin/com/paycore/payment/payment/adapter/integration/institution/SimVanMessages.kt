package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.application.required.AuthorizationCall
import com.paycore.payment.payment.application.required.AuthorizationInquiryCall
import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.UnknownCause
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * PG 쪽 sim-van-1 메시지(AUTH-001 §5.2, golden `van-contract-sim-van-1.golden.json`). 요청은 계약 필드 순서의 compact JSON으로 쓴다(golden 프레임과 바이트 비교).
 * 응답은 엄격 파싱한다: outcome·result별 닫힌 필드 집합, 모르는 필드·null·타입·문법·식별자 형식 위반·중복 키 → FRAMING. 할부 표현은 의미 필드(plan.kind·months)다.
 */
internal object SimVanMessages {
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private val DECISION_ID = Regex("^vd_[0-9A-HJKMNP-TV-Z]{26}$")
    private val PENDING_ID = Regex("^vp_[0-9A-HJKMNP-TV-Z]{26}$")
    private val CLOSURE_ID = Regex("^vc_[0-9A-HJKMNP-TV-Z]{26}$")
    private val APPROVAL_NUMBER = Regex("^[0-9A-Z]{1,32}$")
    private val PTX = Regex("^[!-~]{1,64}$")
    private val CURRENCY = Regex("^[A-Z]{3}$")
    private val TIMESTAMP_TEXT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
    private val DATE_TEXT = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val DECLINES = setOf("ISSUER_DECLINED", "AUTHENTICATION_INVALID", "PLAN_NOT_SUPPORTED")

    private val AUTH_BASE = setOf("type", "inReplyTo", "requestId", "outcome", "respondedAt")
    private val INQUIRY_BASE = setOf("type", "inReplyTo", "inquiryId", "originalRequestId", "result", "respondedAt")
    private val APPROVAL = setOf("approvalNumber", "providerTransactionId", "approvedAmountMinor", "currency", "plan", "cardType", "institutionTransactionDate")
    private const val BUSINESS_DATE = "institutionBusinessDate"

    fun authRequest(call: AuthorizationCall, messageId: String, requestedAt: Instant): ByteArray {
        val r = call.providerRequest
        val o = mapper.createObjectNode()
        o.put("type", "AUTH_REQUEST").put("messageId", messageId).put("requestId", r.requestId).put("institutionAccount", r.institutionAccount)
            .put("merchantRef", call.merchantRef).put("environment", r.environment.name).put("routeRef", call.routeRef).put("amountMinor", call.money.amountMinor)
            .put("currency", call.money.currency.value)
        val plan = o.putObject("plan")
        when (val p = call.plan) {
            PaymentPlan.LumpSum -> plan.put("kind", "LUMP_SUM")
            is PaymentPlan.Installment -> plan.put("kind", "INSTALLMENT").put("months", p.months)
        }
        o.putObject("authentication").put("evidenceRef", call.evidence.evidenceRef).put("providerCode", call.evidence.providerCode)
            .put("issuerCode", call.evidence.issuerCode).put("acquirerCode", call.evidence.acquirerCode)
        o.put("requestedAt", TIMESTAMP.format(requestedAt))
        return mapper.writeValueAsBytes(o)
    }

    fun inquiryRequest(call: AuthorizationInquiryCall, messageId: String, closeIfAbsent: Boolean): ByteArray {
        val r = call.providerRequest
        val o = mapper.createObjectNode()
        o.put("type", "INQUIRY_REQUEST").put("messageId", messageId).put("inquiryId", call.inquiryId).put("originalRequestId", r.requestId)
            .put("institutionAccount", r.institutionAccount).put("environment", r.environment.name).put("closeIfAbsent", closeIfAbsent)
        return mapper.writeValueAsBytes(o)
    }

    /** 승인 응답(스키마 검사 뒤). 상관관계(inReplyTo·requestId echo)는 호출자가 본다. */
    sealed interface AuthReply {
        val inReplyTo: String
        val requestId: String

        data class Decided(override val inReplyTo: String, override val requestId: String, val decisionId: String, val terms: ApprovedTerms?) : AuthReply
        data class Pending(override val inReplyTo: String, override val requestId: String, val pendingId: String) : AuthReply
        data class Refused(override val inReplyTo: String, override val requestId: String) : AuthReply
    }

    /** 조회 응답(스키마 검사 뒤). */
    sealed interface InquiryReply {
        val inReplyTo: String
        val inquiryId: String
        val originalRequestId: String

        data class Found(override val inReplyTo: String, override val inquiryId: String, override val originalRequestId: String, val decisionId: String, val terms: ApprovedTerms?) :
            InquiryReply
        data class Pending(override val inReplyTo: String, override val inquiryId: String, override val originalRequestId: String, val pendingId: String) : InquiryReply
        data class NotFound(override val inReplyTo: String, override val inquiryId: String, override val originalRequestId: String) : InquiryReply
        data class NoEffect(override val inReplyTo: String, override val inquiryId: String, override val originalRequestId: String, val closureId: String) : InquiryReply
        data class Unavailable(override val inReplyTo: String, override val inquiryId: String, override val originalRequestId: String) : InquiryReply
    }

    fun parseAuthReply(payload: ByteArray): AuthReply = guarded {
        val root = tree(payload)
        if (text(root, "type") != "AUTH_RESPONSE") throw framing()
        text(root, "respondedAt", TIMESTAMP_TEXT)
        val inReplyTo = text(root, "inReplyTo")
        val requestId = text(root, "requestId")
        when (text(root, "outcome")) {
            "APPROVED" -> {
                fields(root, AUTH_BASE + "decisionId" + APPROVAL, optional = setOf(BUSINESS_DATE))
                AuthReply.Decided(inReplyTo, requestId, text(root, "decisionId", DECISION_ID), terms(root))
            }
            "DECLINED" -> {
                fields(root, AUTH_BASE + "decisionId" + "declineCategory")
                if (text(root, "declineCategory") !in DECLINES) throw framing()
                AuthReply.Decided(inReplyTo, requestId, text(root, "decisionId", DECISION_ID), null)
            }
            "PENDING" -> {
                fields(root, AUTH_BASE + "pendingId")
                AuthReply.Pending(inReplyTo, requestId, text(root, "pendingId", PENDING_ID))
            }
            "REQUEST_CONFLICT", "REJECTED_CLOSED" -> {
                fields(root, AUTH_BASE)
                AuthReply.Refused(inReplyTo, requestId)
            }
            else -> throw framing()
        }
    }

    fun parseInquiryReply(payload: ByteArray): InquiryReply = guarded {
        val root = tree(payload)
        if (text(root, "type") != "INQUIRY_RESPONSE") throw framing()
        text(root, "respondedAt", TIMESTAMP_TEXT)
        val inReplyTo = text(root, "inReplyTo")
        val inquiryId = text(root, "inquiryId")
        val original = text(root, "originalRequestId")
        when (text(root, "result")) {
            "FOUND" -> when (text(root, "outcome")) {
                "APPROVED" -> {
                    fields(root, INQUIRY_BASE + "decisionId" + "outcome" + APPROVAL, optional = setOf(BUSINESS_DATE))
                    InquiryReply.Found(inReplyTo, inquiryId, original, text(root, "decisionId", DECISION_ID), terms(root))
                }
                "DECLINED" -> {
                    fields(root, INQUIRY_BASE + "decisionId" + "outcome" + "declineCategory")
                    if (text(root, "declineCategory") !in DECLINES) throw framing()
                    InquiryReply.Found(inReplyTo, inquiryId, original, text(root, "decisionId", DECISION_ID), null)
                }
                else -> throw framing()
            }
            "PENDING" -> {
                fields(root, INQUIRY_BASE + "pendingId")
                InquiryReply.Pending(inReplyTo, inquiryId, original, text(root, "pendingId", PENDING_ID))
            }
            "NOT_FOUND" -> {
                fields(root, INQUIRY_BASE)
                InquiryReply.NotFound(inReplyTo, inquiryId, original)
            }
            "NO_EFFECT_CONFIRMED" -> {
                fields(root, INQUIRY_BASE + "closureId")
                InquiryReply.NoEffect(inReplyTo, inquiryId, original, text(root, "closureId", CLOSURE_ID))
            }
            "UNAVAILABLE" -> {
                fields(root, INQUIRY_BASE)
                InquiryReply.Unavailable(inReplyTo, inquiryId, original)
            }
            else -> throw framing()
        }
    }

    private fun terms(root: JsonNode): ApprovedTerms {
        val amount = root.get("approvedAmountMinor")
        if (!amount.isIntegralNumber || !amount.canConvertToLong() || amount.longValue() < 1) throw framing()
        val plan = root.get("plan")
        if (!plan.isObject) throw framing()
        val planFields = plan.properties().map { it.key }.toSet()
        val parsedPlan = when (text(plan, "kind")) {
            "LUMP_SUM" -> if (planFields == setOf("kind")) PaymentPlan.LumpSum else throw framing()
            "INSTALLMENT" -> {
                val months = plan.get("months")
                if (planFields != setOf("kind", "months") || months == null || !months.isInt) throw framing()
                PaymentPlan.Installment(months.intValue())
            }
            else -> throw framing()
        }
        val cardType = CardType.entries.firstOrNull { it.name == text(root, "cardType") } ?: throw framing()
        return ApprovedTerms(
            money = Money(amount.longValue(), CurrencyCode(text(root, "currency", CURRENCY))),
            plan = parsedPlan,
            cardType = cardType,
            approvalNumber = text(root, "approvalNumber", APPROVAL_NUMBER),
            providerTransactionId = text(root, "providerTransactionId", PTX),
            institutionTransactionDate = LocalDate.parse(text(root, "institutionTransactionDate", DATE_TEXT)),
            institutionBusinessDate = root.get(BUSINESS_DATE)?.let { LocalDate.parse(text(root, BUSINESS_DATE, DATE_TEXT)) },
        )
    }

    private fun tree(payload: ByteArray): JsonNode {
        if (payload.isEmpty()) throw framing()
        val root = try {
            mapper.readTree(String(payload, Charsets.UTF_8))
        } catch (e: JacksonException) {
            throw framing()
        }
        if (root == null || !root.isObject) throw framing()
        return root
    }

    private fun fields(node: JsonNode, required: Set<String>, optional: Set<String> = emptySet()) {
        val keys = node.properties().map { it.key }.toSet()
        if (!keys.containsAll(required) || !(required + optional).containsAll(keys)) throw framing()
    }

    private fun text(node: JsonNode, field: String, pattern: Regex? = null): String {
        val v = node.get(field)
        if (v == null || !v.isString) throw framing()
        val s = v.stringValue()
        if (pattern != null && !pattern.matches(s)) throw framing()
        return s
    }

    /** 값 검사 예외(IllegalArgumentException·날짜 형식)도 FRAMING으로 바꾼다. */
    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw framing()
    } catch (e: java.time.DateTimeException) {
        throw framing()
    }

    private fun framing() = SimVanProtocolException(UnknownCause.FRAMING)
}
