package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.application.provided.MALFORMED
import com.paycore.payment.payment.application.provided.MalformedOperatorCommand
import com.paycore.payment.payment.application.provided.OperatorCancelRejectionCode
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorCancelPurpose
import com.paycore.payment.payment.domain.OperatorId
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * ADMIN 실행·기한 확인 POST 본문 엄격 해석(ADMIN-PAYMENT-OPS-001 §4.3 L-1·§4.6·§4.18). 모든 필드 필수, 모르는 필드·중복 key·뒤따르는 토큰·자료형 강제 변환(문자열 숫자·소수 금액·null) 거부,
 * 금액은 JSON 정수 1..Long.MAX. ID는 ADMIN 계약 형식(`apr_`·`adm_`·`pay_` + ULID — 값 타입보다 엄격, I-07), 지문 `oct1:` hex, 통화 대문자 3자, 시각 `…T…Z` ms 3자리, 목적 2종, 근거·사유
 * 형식(앞뒤 공백은 잘라 내지 않고 거부, Unicode 정규화 없음). 거절 분류는 §4.6 표(원문은 결과에 넣지 않는다 — 형식이 맞는 ID만 값으로).
 */
object AdminOperatorCancellationRequestParser {

    sealed interface Parsed {
        data class Ok(val command: OperatorCorrectionCancelCommand) : Parsed

        data class Malformed(val audit: MalformedOperatorCommand) : Parsed
    }

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private val FIELDS = setOf(
        "approvalId", "paymentId", "amountMinor", "currency", "requestedBy", "approvedBy", "reason", "purpose", "approvedTargetFingerprint", "approvedAt", "executeBefore",
        "correlationId",
    )
    private val PURPOSE_FIELDS = setOf("kind", "reference")
    private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
    val APPROVAL_ID = Regex("^apr_$ULID$")
    val OPERATOR_ID = Regex("^adm_$ULID$")
    val PAYMENT_ID = Regex("^pay_$ULID$")
    private val FINGERPRINT = Regex("^oct1:[0-9a-f]{64}$")
    private val CURRENCY = Regex("^[A-Z]{3}$")
    private val TIMESTAMP = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
    private val CORRELATION = Regex("^[!-~]{1,128}$")
    private const val MAX_REFERENCE = 200
    private const val MAX_REASON = 500

    /** [pathApprovalId]가 있으면(기한 확인) 본문 approvalId와 같아야 한다(다르면 REQUEST_MALFORMED). */
    fun parse(body: ByteArray, correlationFallback: String, pathApprovalId: String? = null): Parsed {
        val root = runCatching { mapper.readTree(body) }.getOrNull()
        if (root == null || !root.isObject) return malformed(null, OperatorCancelRejectionCode.REQUEST_MALFORMED, correlationFallback)
        val approvalId = text(root, "approvalId")?.takeIf(APPROVAL_ID::matches)
        val paymentId = text(root, "paymentId")?.takeIf(PAYMENT_ID::matches)
        val requestedBy = text(root, "requestedBy")?.takeIf(OPERATOR_ID::matches)
        val approvedBy = text(root, "approvedBy")?.takeIf(OPERATOR_ID::matches)
        val ids = Ids(approvalId, paymentId, requestedBy, approvedBy)
        val correlationId = text(root, "correlationId")?.takeIf(CORRELATION::matches)
        val purpose = purpose(root.get("purpose"))
        val amount = root.get("amountMinor")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()?.takeIf { it >= 1 }
        val currency = text(root, "currency")?.takeIf(CURRENCY::matches)
        val fingerprint = text(root, "approvedTargetFingerprint")?.takeIf(FINGERPRINT::matches)
        val approvedAt = instant(text(root, "approvedAt"))
        val executeBefore = instant(text(root, "executeBefore"))
        val reason = text(root, "reason")?.takeIf(::validReason)
        val otherDefect = root.propertyNames().toSet() != FIELDS || correlationId == null || purpose == null || amount == null || currency == null ||
            fingerprint == null || approvedAt == null || executeBefore == null || reason == null ||
            (pathApprovalId != null && pathApprovalId != text(root, "approvalId"))
        val money = if (amount != null && currency != null) runCatching { Money(amount, CurrencyCode(currency)) }.getOrNull() else null
        val categories = listOfNotNull(
            OperatorCancelRejectionCode.APPROVAL_ID_MALFORMED.takeIf { approvalId == null },
            OperatorCancelRejectionCode.PAYMENT_ID_MALFORMED.takeIf { paymentId == null },
            OperatorCancelRejectionCode.OPERATOR_ID_MALFORMED.takeIf { requestedBy == null || approvedBy == null },
            OperatorCancelRejectionCode.REQUEST_MALFORMED.takeIf { otherDefect || money == null },
        )
        val fallback = correlationId ?: correlationFallback
        if (categories.isNotEmpty()) return malformed(ids, categories.singleOrNull() ?: OperatorCancelRejectionCode.REQUEST_MALFORMED, fallback)
        return Parsed.Ok(
            OperatorCorrectionCancelCommand(
                paymentId = checkNotNull(paymentId), money = checkNotNull(money), requestedBy = OperatorId(checkNotNull(requestedBy)),
                approvedBy = OperatorId(checkNotNull(approvedBy)), approvalId = OperatorApprovalId(checkNotNull(approvalId)), reason = checkNotNull(reason),
                purpose = checkNotNull(purpose), approvedTargetFingerprint = checkNotNull(fingerprint), approvedAt = checkNotNull(approvedAt),
                executeBefore = checkNotNull(executeBefore), correlationId = checkNotNull(correlationId),
            ),
        )
    }

    private data class Ids(val approvalId: String?, val paymentId: String?, val requestedBy: String?, val approvedBy: String?)

    private fun malformed(ids: Ids?, code: OperatorCancelRejectionCode, correlationId: String) = Parsed.Malformed(
        MalformedOperatorCommand(
            ids?.approvalId ?: MALFORMED, ids?.paymentId ?: MALFORMED, ids?.requestedBy ?: MALFORMED, ids?.approvedBy ?: MALFORMED, code, correlationId,
        ),
    )

    private fun text(node: JsonNode, field: String): String? = node.get(field)?.takeIf { it.isString }?.stringValue()

    private fun instant(value: String?): Instant? = value?.takeIf(TIMESTAMP::matches)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun purpose(node: JsonNode?): OperatorCancelPurpose? {
        if (node == null || !node.isObject || node.propertyNames().toSet() != PURPOSE_FIELDS) return null
        val reference = text(node, "reference")?.takeIf(::validReference) ?: return null
        return when (text(node, "kind")) {
            "INCIDENT_OR_RECON_CORRECTION" -> OperatorCancelPurpose.IncidentOrReconCorrection(reference)
            "MERCHANT_TERMINATED_OBLIGATION" -> OperatorCancelPurpose.MerchantTerminatedObligation(reference)
            else -> null
        }
    }

    /** §4.18 근거: 1~200 UTF-16 단위, 올바른 UTF-16, Unicode 문자·숫자·U+0020·`- _ . : / #`만, 첫·끝 공백 거부. */
    fun validReference(s: String): Boolean =
        s.length in 1..MAX_REFERENCE && wellFormed(s) && s.first() != ' ' && s.last() != ' ' &&
            s.codePoints().allMatch { Character.isLetter(it) || Character.isDigit(it) || it == ' '.code || it.toChar() in "-_.:/#" && it < 0x80 }

    /** §4.18 사유: 1~500 UTF-16 단위, 줄바꿈(LF·CR) 외 제어 문자·형식 문자(Cf — RLO·zero-width 등, I-102)·사설·미할당 거부, 앞뒤 공백 거부. */
    fun validReason(s: String): Boolean =
        s.length in 1..MAX_REASON && wellFormed(s) && !Character.isWhitespace(s.first()) && !Character.isWhitespace(s.last()) &&
            s.codePoints().allMatch {
                val type = Character.getType(it)
                (type != Character.CONTROL.toInt() || it == '\n'.code || it == '\r'.code) &&
                    type != Character.FORMAT.toInt() && type != Character.PRIVATE_USE.toInt() && type != Character.UNASSIGNED.toInt()
            }

    private fun wellFormed(s: String): Boolean = s.codePoints().noneMatch { Character.getType(it) == Character.SURROGATE.toInt() }
}
