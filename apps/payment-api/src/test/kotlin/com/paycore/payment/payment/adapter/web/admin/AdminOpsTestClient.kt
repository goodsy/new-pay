package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.application.CancelFixture
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorCancelPurpose
import com.paycore.payment.payment.domain.OperatorCancelTarget
import com.paycore.payment.payment.domain.OperatorId
import com.paycore.payment.payment.domain.PaymentId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * ADMIN 채널 실제 HTTP 시험 도구(ADMIN-PAYMENT-OPS-001 S3, src/test 전용): 내부 포트로 golden 시험 키 서명(시각 = 실제 시스템 시계 — 서명 시각 창은 서버 시계) 요청을 보낸다.
 * 명령 본문은 ADMIN 계약 모양(§4.3)이고 승인 대상 지문은 domain `OperatorCancelTarget`(golden ①로 독립 대조됨)으로 만든다. 승인 시각·기한은 PAYMENT 업무 시계(시험 T0) 기준.
 */
class AdminOpsTestClient(private val port: Int) {
    data class Response(val status: Int, val body: String) {
        val json: JsonNode by lazy { MAPPER.readTree(body) }
    }

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun post(path: String, body: String): Response = send("POST", path, body.toByteArray(Charsets.UTF_8))

    fun get(path: String): Response = send("GET", path, ByteArray(0))

    fun send(method: String, path: String, body: ByteArray, timeout: Duration = Duration.ofSeconds(60)): Response {
        val r = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(timeout)
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
            .header("Content-Type", "application/json")
            .header(AdminCallSignature.HEADER, AdminChannelFixture.header(method, path, body))
            .build()
        val response = http.send(r, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return Response(response.statusCode(), response.body())
    }

    companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()
        const val EXECUTE = "/internal/v1/admin/operator-cancellations"
        const val REQUESTER = "adm_01JC00000000000000000000R1"
        const val APPROVER = "adm_01JC00000000000000000000A1"
        private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private val random = SecureRandom()
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun ulid(): String = (1..26).map { CROCKFORD[random.nextInt(32)] }.joinToString("")

        fun newApprovalId(): String = "apr_" + ulid()

        fun newPaymentId(): String = "pay_" + ulid()

        fun deadline(approvalId: String) = "$EXECUTE/$approvalId/deadline-confirmation"

        /** 실행 명령 본문(§4.3 필드 순서). [revision]은 승인 시점 잔액 revision(첫 취소 = 0). */
        @Suppress("LongParameterList")
        fun body(
            a: CancelFixture.Authorized?,
            approvalId: String = newApprovalId(),
            paymentId: String = a!!.paymentId.value,
            amountMinor: Long = a!!.money.amountMinor,
            currency: String = a?.money?.currency?.value ?: "KRW",
            requestedBy: String = REQUESTER,
            approvedBy: String = APPROVER,
            reason: String = "Demo correction after reconciliation case review",
            purposeKind: String = "INCIDENT_OR_RECON_CORRECTION",
            reference: String = "RECON-2026-0001",
            revision: Long = 0,
            approvedAt: Instant = com.paycore.payment.payment.application.ConfirmFixture.T0.minusSeconds(60),
            executeBefore: Instant = approvedAt.plus(Duration.ofHours(24)),
            correlationId: String = "corr-" + ulid(),
            fingerprint: String? = null,
        ): String {
            val purpose = if (purposeKind == "MERCHANT_TERMINATED_OBLIGATION") OperatorCancelPurpose.MerchantTerminatedObligation(reference)
            else OperatorCancelPurpose.IncidentOrReconCorrection(reference)
            val oct1 = fingerprint ?: OperatorCancelTarget(
                PaymentId(paymentId), Money(amountMinor, com.paycore.payment.payment.domain.CurrencyCode(currency)), revision, OperatorApprovalId(approvalId),
                OperatorId(requestedBy), OperatorId(approvedBy), purpose,
            ).fingerprint()
            return MAPPER.writeValueAsString(
                linkedMapOf(
                    "approvalId" to approvalId, "paymentId" to paymentId, "amountMinor" to amountMinor, "currency" to currency, "requestedBy" to requestedBy,
                    "approvedBy" to approvedBy, "reason" to reason, "purpose" to linkedMapOf("kind" to purposeKind, "reference" to reference),
                    "approvedTargetFingerprint" to oct1, "approvedAt" to TIME.format(approvedAt), "executeBefore" to TIME.format(executeBefore), "correlationId" to correlationId,
                ),
            )
        }

        fun approvalIdOf(body: String): String = MAPPER.readTree(body).get("approvalId").asString()
    }
}
