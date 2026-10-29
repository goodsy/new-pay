package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.ApiResponses
import com.paycore.payment.payment.application.provided.OperatorDecisionState
import com.paycore.payment.payment.application.provided.OperatorDecisionView
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * ADMIN 채널 응답(ADMIN-PAYMENT-OPS-001 §4.3·§4.5): 오류 본문 `{"error":"<CODE>"}`(고정 code만 — 요청 값·헤더·사유 원문 없음), requestId는 헤더(`PayCore-Request-Id`)로만, 시각은
 * `…T…Z` ms 3자리. `Cache-Control: no-store`는 체인의 기본 보안 헤더가 붙인다(§4.2 응답). 카드·기관 정보·사유·근거 문구는 어떤 응답에도 없다.
 */
object AdminChannelResponses {
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val QUERY_NOT_ALLOWED = "QUERY_NOT_ALLOWED"
    const val REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE"
    const val REQUEST_UNREADABLE = "REQUEST_UNREADABLE"
    const val PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND"
    const val REQUEST_MALFORMED = "REQUEST_MALFORMED"
    const val TEMPORARILY_UNAVAILABLE = "TEMPORARILY_UNAVAILABLE"
    const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
    const val ADMIN_READ_BUSY = "ADMIN_READ_BUSY"
    const val ADMIN_COMMAND_BUSY = "ADMIN_COMMAND_BUSY"

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun write(request: HttpServletRequest, response: HttpServletResponse, status: Int, code: String) {
        response.status = status
        response.setHeader(ApiResponses.REQUEST_ID_HEADER, ApiResponses.requestId(request))
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.outputStream.write(ApiResponses.mapper.writeValueAsBytes(mapOf("error" to code)))
    }

    fun json(request: HttpServletRequest, status: Int, body: Map<String, Any?>): ResponseEntity<ByteArray> =
        ResponseEntity.status(status).header(ApiResponses.REQUEST_ID_HEADER, ApiResponses.requestId(request)).contentType(MediaType.APPLICATION_JSON)
            .body(ApiResponses.mapper.writeValueAsBytes(body))

    fun error(request: HttpServletRequest, status: Int, code: String): ResponseEntity<ByteArray> = json(request, status, mapOf("error" to code))

    fun time(at: Instant): String = TIME.format(at)

    /** 결정 조회 모양(§4.3 공통 필드 L-b). NONE은 `{"decision":"NONE"}`. 값이 없는 필드는 내보내지 않는다. */
    fun decision(v: OperatorDecisionView): LinkedHashMap<String, Any?> {
        val body = linkedMapOf<String, Any?>("decision" to v.state.name)
        if (v.state == OperatorDecisionState.NONE) return body
        if (v.state == OperatorDecisionState.STARTED) {
            body["cancellationId"] = v.cancellationId
            body["cancellationStatus"] = v.cancellationStatus?.name
        } else {
            body["code"] = v.code
        }
        body["paymentId"] = v.paymentId
        body["amountMinor"] = v.amountMinor
        body["currency"] = v.currency
        body["requestedBy"] = v.requestedBy
        body["approvedBy"] = v.approvedBy
        body["approvedTargetFingerprint"] = v.approvedTargetFingerprint
        v.contentHash?.let { body["contentHash"] = it }
        body["decidedAt"] = v.decidedAt?.let(::time)
        body["legacy"] = v.legacy
        return body
    }

    /** 결정 목록 항목(§4.17 — 사유·근거 문구 없음). */
    fun listItem(v: OperatorDecisionView): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
        "seq" to v.seq, "approvalId" to v.approvalId, "paymentId" to v.paymentId, "decision" to v.state.name,
    ).also { m ->
        v.code?.let { m["code"] = it }
        v.cancellationId?.let { m["cancellationId"] = it }
        m["amountMinor"] = v.amountMinor
        m["currency"] = v.currency
        m["requestedBy"] = v.requestedBy
        m["approvedBy"] = v.approvedBy
        m["approvedTargetFingerprint"] = v.approvedTargetFingerprint
        m["contentHash"] = v.contentHash
        m["decidedAt"] = v.decidedAt?.let(::time)
        m["recordedAt"] = v.recordedAt?.let(::time)
        m["origin"] = v.origin
    }
}
