package com.paycore.admin.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PAYMENT 내부 API 시험 대역(ADMIN-PAYMENT-OPS-001 §7.6 `StubPaymentOpsServer` — 실제 HTTP). 시험 설정 `paycore.admin.payment-ops.base-url`의 고정 포트에서 한 번 뜨고
 * (새 Spring 컨텍스트를 만들지 않게) 시험마다 응답을 바꾼다. 받은 요청(method·경로·query·서명 헤더·본문 바이트)을 기록하고, 서명은 **앱 코드와 독립으로**
 * (javax.crypto 직접) 계약 golden ② 시험 키로 검증해 기록한다(`signatureValid`).
 */
object StubPaymentOpsServer {
    const val PORT = 18493
    private const val LIST_PREFIX = "/internal/v1/admin/operator-decisions/after/"
    private val DECISION_PATH = Regex("^/internal/v1/admin/operator-cancellations/apr_[0-9A-Z]{26}$")
    private val DEADLINE_PATH = Regex("^/internal/v1/admin/operator-cancellations/apr_[0-9A-Z]{26}/deadline-confirmation$")
    private const val KEY_BASE64 = "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx"

    class Received(val method: String, val path: String, val query: String?, val signature: String?, val body: ByteArray, val signatureValid: Boolean)

    class Reply(val status: Int, val body: String, val delayMillis: Long = 0)

    val received = CopyOnWriteArrayList<Received>()
    private val targets = ConcurrentHashMap<String, Reply>()
    private val executions = ConcurrentHashMap<String, Reply>()
    private val decisions = ConcurrentHashMap<String, Reply>()
    private val deadlines = ConcurrentHashMap<String, Reply>()
    private val lists = ConcurrentHashMap<Long, Reply>()

    @Volatile
    private var defaultExecution: Reply = Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}")

    @Volatile
    private var server: HttpServer? = null

    @Synchronized
    fun start() {
        if (server != null) return
        server = HttpServer.create(InetSocketAddress("127.0.0.1", PORT), 0).apply {
            executor = Executors.newFixedThreadPool(8)
            createContext("/") { handle(it) }
            start()
        }
    }

    /** 대상 조회 응답(paymentId별). 기본은 404 PAYMENT_NOT_FOUND. */
    fun target(paymentId: String, reply: Reply) {
        targets[paymentId] = reply
    }

    fun targetFound(
        paymentId: String, revision: Long = 3, cancellable: Boolean = true, currency: String? = "KRW", authorized: Long = 10_000, cancellableMinor: Long = 10_000,
        inFlight: Boolean = false, onHold: Boolean = false,
    ) = target(
        paymentId,
        Reply(
            200,
            "{\"paymentId\":\"$paymentId\",\"paymentStatus\":\"AUTHORIZED\",\"cancellable\":$cancellable,\"currency\":${currency?.let { "\"$it\"" } ?: "null"}," +
                "\"authorizedMinor\":$authorized,\"cancellableMinor\":$cancellableMinor,\"balanceRevision\":$revision,\"onHold\":$onHold,\"inFlightCancel\":$inFlight}",
        ),
    )

    /** 실행 POST 응답(본문의 approvalId별, 없으면 [defaultExecution]). */
    fun execution(approvalId: String, reply: Reply) {
        executions[approvalId] = reply
    }

    /** 기록 결정 조회 응답(approvalId별, 기본 200 `{"decision":"NONE"}`). */
    fun decision(approvalId: String, reply: Reply) {
        decisions[approvalId] = reply
    }

    /** 기한 확인 응답(approvalId별, 기본 200 `{"decision":"NONE","confirmed":false}`). */
    fun deadline(approvalId: String, reply: Reply) {
        deadlines[approvalId] = reply
    }

    /** 결정 응답 본문(golden ③ 공통 필드 모양). [cancellationId]가 있으면 STARTED, 아니면 REJECTED([code]). */
    fun decisionBody(
        paymentId: String, amountMinor: Long, requestedBy: String, approvedBy: String, fingerprint: String, cancellationId: String? = null, code: String? = null,
        confirmed: Boolean? = null, currency: String = "KRW",
    ): String {
        val head = if (cancellationId != null) "\"decision\":\"STARTED\",\"cancellationId\":\"$cancellationId\",\"cancellationStatus\":\"COMPLETED\""
        else "\"decision\":\"REJECTED\",\"code\":\"$code\""
        return "{$head,\"paymentId\":\"$paymentId\",\"amountMinor\":$amountMinor,\"currency\":\"$currency\",\"requestedBy\":\"$requestedBy\",\"approvedBy\":\"$approvedBy\"," +
            "\"approvedTargetFingerprint\":\"$fingerprint\",\"contentHash\":\"opd1:${"0".repeat(64)}\",\"decidedAt\":\"2026-10-27T01:02:03.456Z\",\"legacy\":false" +
            (confirmed?.let { ",\"confirmed\":$it" } ?: "") + "}"
    }

    /** 결정 목록 응답(요청 watermark별, 기본 = 빈 페이지·같은 watermark). */
    fun decisionList(afterWatermark: Long, reply: Reply) {
        lists[afterWatermark] = reply
    }

    /** 결정 목록 페이지 본문(golden ③ `listPage` 모양). */
    fun listPage(items: List<String>, nextWatermark: Long): String =
        "{\"items\":[${items.joinToString(",")}],\"nextWatermark\":$nextWatermark,\"serverTime\":\"2026-10-28T00:00:00.000Z\"}"

    /** 결정 목록 한 행(cancellationId가 있으면 STARTED, 아니면 REJECTED(code)). */
    fun listItem(
        seq: Long, approvalId: String, paymentId: String, requestedBy: String, approvedBy: String, fingerprint: String, cancellationId: String? = null,
        code: String? = null, amountMinor: Long = 7_000, currency: String = "KRW", recordedAt: String = "2026-10-28T00:00:00.000Z",
    ): String {
        val decision = if (cancellationId != null) "\"decision\":\"STARTED\",\"cancellationId\":\"$cancellationId\"" else "\"decision\":\"REJECTED\",\"code\":\"$code\""
        return "{\"seq\":$seq,\"approvalId\":\"$approvalId\",\"paymentId\":\"$paymentId\",$decision,\"amountMinor\":$amountMinor,\"currency\":\"$currency\"," +
            "\"requestedBy\":\"$requestedBy\",\"approvedBy\":\"$approvedBy\",\"approvedTargetFingerprint\":\"$fingerprint\",\"contentHash\":\"opd1:${"0".repeat(64)}\"," +
            "\"decidedAt\":\"$recordedAt\",\"recordedAt\":\"$recordedAt\",\"origin\":\"EXECUTION_COMMAND\"}"
    }

    fun requestsFor(approvalId: String): List<Received> = received.filter { r -> r.body.toString(Charsets.UTF_8).contains("\"approvalId\":\"$approvalId\"") }

    fun requestsForPath(path: String): List<Received> = received.filter { it.path == path }

    private fun handle(exchange: HttpExchange) {
        exchange.use { ex ->
            val body = ex.requestBody.readAllBytes()
            val path = ex.requestURI.rawPath
            val signature = ex.requestHeaders.getFirst("PayCore-Admin-Signature")
            received += Received(ex.requestMethod, path, ex.requestURI.rawQuery, signature, body, verify(ex.requestMethod, path, signature, body))
            val reply = when {
                ex.requestMethod == "GET" && path.endsWith("/operator-cancel-target") ->
                    targets[path.removePrefix("/internal/v1/admin/payments/").removeSuffix("/operator-cancel-target")]
                        ?: Reply(404, "{\"error\":\"PAYMENT_NOT_FOUND\"}")
                ex.requestMethod == "GET" && path.startsWith(LIST_PREFIX) -> path.removePrefix(LIST_PREFIX).toLong().let { wm ->
                    lists[wm] ?: Reply(200, listPage(emptyList(), wm))
                }
                ex.requestMethod == "GET" && DECISION_PATH.matches(path) ->
                    decisions[path.substringAfterLast('/')] ?: Reply(200, "{\"decision\":\"NONE\"}")
                ex.requestMethod == "POST" && DEADLINE_PATH.matches(path) ->
                    deadlines[path.removeSuffix("/deadline-confirmation").substringAfterLast('/')] ?: Reply(200, "{\"decision\":\"NONE\",\"confirmed\":false}")
                ex.requestMethod == "POST" && path == "/internal/v1/admin/operator-cancellations" ->
                    Regex("\"approvalId\":\"(apr_[0-9A-Z]{26})\"").find(body.toString(Charsets.UTF_8))?.groupValues?.get(1)?.let { executions[it] } ?: defaultExecution
                else -> Reply(404, "{\"error\":\"NOT_FOUND\"}")
            }
            if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.write(bytes)
        }
    }

    private fun verify(method: String, path: String, header: String?, body: ByteArray): Boolean {
        val m = Regex("^t=([0-9]{1,10}),kid=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})$").matchEntire(header ?: return false) ?: return false
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val canonical = "paycore.admin-call.v1\n$method\n$path\n${m.groupValues[1]}\n$bodyHash"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(Base64.getDecoder().decode(KEY_BASE64), "HmacSHA256")) }
        val expected = mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return m.groupValues[2] == "adm-golden-k1" && MessageDigest.isEqual(expected.toByteArray(), m.groupValues[3].toByteArray())
    }
}
