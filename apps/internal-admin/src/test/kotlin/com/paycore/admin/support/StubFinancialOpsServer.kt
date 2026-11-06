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
 * FINANCIAL 매입 현황 내부 조회 API 시험 대역(ACQ-VIEW-001 — 실제 HTTP, `StubMasterOpsServer`와 같은 방식). 시험 설정 `paycore.admin.financial-ops.base-url`의 고정 포트에서 한 번
 * 뜨고 시험마다 응답을 바꾼다. 받은 요청을 기록하고 서명은 **앱 코드와 독립으로**(javax.crypto 직접) 시험 키 fops-test-k1로 검증한다. 응답 본문은 FINANCIAL 계약 모양(B1 시험이 만든
 * 실제 응답 모양 — financial-batch `AdminAcquisitionApiSpec`·`AcquisitionFileViewReaderSpec`의 키 순서)의 문자열이다.
 */
object StubFinancialOpsServer {
    const val PORT = 18495
    const val KEY_ID = "fops-test-k1"
    const val KEY_BASE64 = "dGVzdC1vbmx5LWZpbmFuY2lhbC1hZG1pbi1jaGFubmVsLWtleS0wMDAx"

    class Received(val method: String, val path: String, val query: String?, val signature: String?, val body: ByteArray, val signatureValid: Boolean)

    class Reply(val status: Int, val body: String, val delayMillis: Long = 0)

    val received = CopyOnWriteArrayList<Received>()
    private val replies = ConcurrentHashMap<String, Reply>()

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

    /** 경로별 응답. 등록되지 않은 경로는 404 `ACQUISITION_FILE_NOT_FOUND`. 검색은 [SEARCH] 경로. */
    fun reply(path: String, reply: Reply) {
        replies[path] = reply
    }

    fun requestsForPath(path: String): List<Received> = received.filter { it.path == path }

    private fun handle(exchange: HttpExchange) {
        exchange.use { ex ->
            val body = ex.requestBody.readAllBytes()
            val path = ex.requestURI.rawPath
            val signature = ex.requestHeaders.getFirst("PayCore-Admin-Signature")
            received += Received(ex.requestMethod, path, ex.requestURI.rawQuery, signature, body, verify(signature, ex.requestMethod, path, body))
            val reply = replies[path] ?: Reply(404, "{\"error\":\"ACQUISITION_FILE_NOT_FOUND\"}")
            if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.write(bytes)
        }
    }

    private fun verify(header: String?, method: String, path: String, body: ByteArray): Boolean {
        val m = Regex("^t=([0-9]{1,10}),kid=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})$").matchEntire(header ?: return false) ?: return false
        if (m.groupValues[2] != KEY_ID) return false
        val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val canonical = listOf("paycore.admin-call.v1", method, path, m.groupValues[1], sha).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(Base64.getDecoder().decode(KEY_BASE64), "HmacSHA256")) }
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } == m.groupValues[3]
    }

    const val SEARCH = "/internal/v1/admin/acquisition-file-searches"

    fun filePath(fileId: String) = "/internal/v1/admin/acquisition-files/$fileId"

    fun recordsPath(fileId: String) = "/internal/v1/admin/acquisition-files/$fileId/record-searches"

    /** 목록 항목(B1 응답 모양). [extra]로 허용되지 않는 필드를 끼워 넣어 fail-closed를 시험한다. */
    fun itemJson(
        fileId: String,
        businessDate: String = fileId.split('.')[3].let { "${it.take(4)}-${it.substring(4, 6)}-${it.takeLast(2)}" },
        attention: List<String> = emptyList(),
        resultState: String = "COMPLETE",
        extra: String = "",
    ): String {
        val acquirer = fileId.split('.')[1]
        return """{"fileId":"$fileId","acquirerCode":"$acquirer","environment":"TEST","businessDate":"$businessDate","fileVersion":1,"originalFileId":null,""" +
            """"recordCount":2,"saleCount":2,"saleAmountMinor":30000,"cancelCount":0,"cancelAmountMinor":0,"netAmountMinor":30000,"currency":"KRW",""" +
            """"transmissionState":"TRANSMITTED","receiptState":"RECEIVED","resultState":"$resultState","sealedAt":"2026-10-20T14:05:00.000Z",""" +
            """"transmittedAt":"2026-10-20T14:06:00.000Z","recordStatusCounts":{"awaiting":0,"accepted":2,"rejected":0,"fileRejected":0,"unknown":0,"conflict":0},""" +
            """"attention":[${attention.joinToString(",") { "\"$it\"" }}],"reacquisitionCandidate":false$extra}"""
    }

    fun pageJson(vararg items: String, nextCursor: String = "null"): String = """{"items":[${items.joinToString(",")}],"nextCursor":$nextCursor}"""

    /** 레코드(B1 모양 — 결과 행 응답 참조는 `responseRef`). [resultExtra]·[recordExtra]로 허용되지 않는 필드를 끼워 넣는다. */
    @Suppress("LongParameterList")
    fun recordJson(
        seq: Int,
        status: String = "ACCEPTED",
        recordExtra: String = "",
        responseRef: String = """{"kind":"RESULT","sequenceNo":1}""",
        resultExtra: String = "",
        result: String = "ACCEPTED",
        reasonCode: String? = null,
        transactionId: String = "ptx_auth_pay_fx_$seq",
    ): String =
        """{"seq":$seq,"recordType":"SALE","transactionId":"$transactionId","originalTransactionId":null,"merchantAccountId":"demo-mid-001",""" +
            """"institutionTransactionDate":"2026-11-05","eventAt":"2026-10-20T01:02:03.456Z","currency":"KRW","approvedAmountMinor":10000,""" +
            """"nettedCancelAmountMinor":0,"recordAmountMinor":10000,"generation":0,"reacquisitionOf":null,"status":"$status"$recordExtra,""" +
            """"results":[{"entryNo":1,"result":"$result","observedResult":null,"reasonCode":${reasonCode?.let { "\"$it\"" } ?: "null"},"source":"RESULT_FILE","responseRef":$responseRef,""" +
            """"recognizedAt":"2026-10-20T15:00:00.000Z"$resultExtra}]}"""

    fun recordsJson(vararg records: String, nextCursor: String = "null"): String = """{"items":[${records.joinToString(",")}],"nextCursor":$nextCursor}"""

    /** 기관 응답 기록 두 건(RESULT 1회·ACK 1회). [ackOutcome]·[ackReasonCode]로 FILE_REJECTED ACK의 사유 코드(예: 숫자 `1001`)를 시험한다. */
    fun responsesJson(ackOutcome: String = "RECEIVED", ackReasonCode: String? = null): String =
        """{"total":2,"quarantined":0,"latest":[{"kind":"RESULT","sequenceNo":1,"disposition":"APPLIED","quarantineReason":null,"ackOutcome":null,"ackReasonCode":null,""" +
            """"evidence":"RESULT_HASH_MATCH","institutionAt":"2026-10-20T14:59:50.000Z","observedAt":"2026-10-20T15:00:00.000Z"},{"kind":"ACK","sequenceNo":1,""" +
            """"disposition":"APPLIED","quarantineReason":null,"ackOutcome":"$ackOutcome","ackReasonCode":${ackReasonCode?.let { "\"$it\"" } ?: "null"},""" +
            """"evidence":"ACK_HASH_MATCH","institutionAt":"2026-10-20T14:59:30.000Z","observedAt":"2026-10-20T15:00:00.000Z"}]}"""

    fun detailJson(
        fileId: String,
        records: String = recordsJson(recordJson(1), recordJson(2)),
        attention: List<String> = emptyList(),
        responses: String = responsesJson(),
    ): String {
        val file = itemJson(fileId, attention = attention).removeSuffix("}") +
            ""","cutoffPolicyVersion":"acq-cutoff-demo-1","cutoffAt":"2026-10-20T14:00:00.000Z","contentSha256":"${"5c8b8097".repeat(8)}","contentBytes":495,""" +
            """"transmissionLeaseUntil":null,"updatedAt":"2026-10-20T15:00:00.000Z"}"""
        return """{"file":$file,"attempts":{"total":1,"latest":[{"attemptNo":1,"resumeState":"SEALED","claimedAt":"2026-10-20T14:06:00.000Z",""" +
            """"leaseUntil":"2026-10-20T14:08:00.000Z","outcome":"DELIVERED","observed":null,"evidence":"RENAMED_AND_VERIFIED","reason":null,""" +
            """"resultingState":"TRANSMITTED","decidedAt":"2026-10-20T14:06:00.000Z"}]},"responses":$responses,"records":$records,"readAt":"2026-11-06T10:00:00.000Z"}"""
    }
}
