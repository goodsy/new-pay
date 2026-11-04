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
 * MASTER 내부 조회 API 시험 대역(MERCHANT-OPS-001 — 실제 HTTP). 시험 설정 `paycore.admin.master-ops.base-url`의 고정 포트에서 한 번 뜨고 시험마다 응답을 바꾼다. 받은 요청
 * (method·경로·query·서명 헤더·본문)을 기록하고 서명은 **앱 코드와 독립으로**(javax.crypto 직접) 시험 키 mops-test-k1로 검증한다(`signatureValid`). 응답 본문은 시험이 준
 * 문자열 그대로(MASTER 계약 모양 — [detailJson] 등).
 */
object StubMasterOpsServer {
    const val PORT = 18494
    const val KEY_ID = "mops-test-k1"
    const val KEY_BASE64 = "dGVzdC1vbmx5LW1hc3Rlci1hZG1pbi1jaGFubmVsLWtleS0wMDAx"

    class Received(val method: String, val path: String, val query: String?, val signature: String?, val body: ByteArray, val signatureValid: Boolean)

    /**
     * [delayMillis]: 헤더 전 지연. [bodyDripMillis]: 헤더와 본문 첫 바이트를 보낸 **뒤** 나머지 본문을 이만큼 늦게 보낸다(본문 읽기 시간 상한 시험 — MINOR-3).
     */
    class Reply(val status: Int, val body: String, val delayMillis: Long = 0, val bodyDripMillis: Long = 0)

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

    /** 경로별 응답. 등록되지 않은 경로는 404 `MERCHANT_NOT_FOUND`. 검색은 [SEARCH] 경로. */
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
            val reply = replies[path] ?: Reply(404, "{\"error\":\"MERCHANT_NOT_FOUND\"}")
            if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty() && reply.bodyDripMillis > 0) {
                ex.responseBody.write(bytes, 0, 1)
                ex.responseBody.flush()
                runCatching {
                    Thread.sleep(reply.bodyDripMillis)
                    ex.responseBody.write(bytes, 1, bytes.size - 1)
                }
            } else if (bytes.isNotEmpty()) {
                ex.responseBody.write(bytes)
            }
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

    const val SEARCH = "/internal/v1/admin/merchant-searches"
    const val INSTITUTION_CANARY = "SIMMERCH0001"

    fun merchantPath(merchantId: String) = "/internal/v1/admin/merchants/$merchantId"

    fun historyPath(merchantId: String, after: Long) = "/internal/v1/admin/merchants/$merchantId/history/after/$after"

    fun directoryJson(merchantId: String, businessId: String, masked: String = "123*******"): String =
        """{"items":[{"merchantId":"$merchantId","merchantName":"Demo Merchant","businessId":"$businessId","businessName":"Demo Business",""" +
            """"registrationNumberMasked":"$masked","lifecycle":"ACTIVE","entitlementRevision":5,"midCount":1,"createdAt":"2026-10-09T02:00:00.000Z"}],"nextCursor":null}"""

    const val EMPTY_DIRECTORY = """{"items":[],"nextCursor":null}"""

    /** MASTER 상세 계약 모양(카드 Route는 인수사·Route 참조·상태만). [routeExtra]로 허용되지 않는 필드를 끼워 넣어 fail-closed를 시험한다. */
    fun detailJson(merchantId: String, routeExtra: String = ""): String {
        val ulid = merchantId.removePrefix("mer_")
        return """{"merchant":{"merchantId":"$merchantId","name":"Demo Merchant","lifecycle":"ACTIVE","entitlementRevision":5,"createdAt":"2026-10-09T02:00:00.000Z"},""" +
            """"business":{"businessId":"biz_$ulid","name":"Demo Business","registrationNumberMasked":"123*******","lifecycle":"ACTIVE"},""" +
            """"sites":[{"merchantSiteId":"site_$ulid","channelType":"WEB","channelIdentifier":"shop.example.test","lifecycle":"ACTIVE","review":{"decision":"APPROVED","decidedAt":"2026-10-09T02:00:00.000Z"}}],""" +
            """"contracts":[{"contractId":"ctr_$ulid","createdAt":"2026-10-09T02:00:00.000Z","revisions":[{"revisionNo":1,"scopeKind":"MERCHANT","effectiveFrom":"2026-10-09T02:00:00.000Z","effectiveTo":null,"approvedAt":"2026-10-09T02:00:00.000Z","scopeSiteIds":[]}],""" +
            """"agreements":[{"agreementId":"csa_$ulid","serviceKind":"CARD_PG_PAYMENT","addedAt":"2026-10-09T02:00:00.000Z"}]}],""" +
            """"accounts":[{"merchantAccountId":"mid_$ulid","lifecycle":"ACTIVE","isDefault":true,"issuingContractId":"ctr_$ulid","issuingAgreementId":"csa_$ulid","issuingRevisionNo":1,"issuedAt":"2026-10-09T02:00:00.000Z",""" +
            """"assignments":[{"assignmentId":"asg_$ulid","merchantSiteId":"site_$ulid","role":"PRIMARY","status":"ACTIVE","effectiveFrom":"2026-10-09T02:00:00.000Z","effectiveTo":null}],""" +
            """"paymentMethods":[{"activationId":"act_$ulid","paymentMethod":"CARD","status":"ACTIVE","registeredAt":"2026-10-09T02:00:00.000Z"}],""" +
            """"currencies":[{"enablementId":"cur_$ulid","currencyCode":"KRW","effectiveFrom":"2026-10-09T02:00:00.000Z","effectiveTo":null,"endedAt":null}],""" +
            """"cardRoutes":[{"activationId":"cra_$ulid","paymentMethod":"CARD","acquirerCode":"SIMCARD01","routeRef":"SIMVAN01","status":"ACTIVE","registeredAt":"2026-10-09T02:00:00.000Z"$routeExtra}]}],""" +
            """"limits":[{"policyId":"tlp_$ulid","scopeKind":"CSA_DEFAULT","agreementId":"csa_$ulid","merchantAccountId":null,"currencyCode":"KRW","perTxKind":"LIMITED","perTxAmount":1000000,"dailyKind":"LIMITED","dailyAmount":5000000,"effectiveFrom":"2026-10-09T15:00:00.000Z","effectiveTo":null,"endedAt":null}],""" +
            """"propagation":{"latestRevision":5,"latestRevisionIssuedAt":"2026-10-09T02:00:00.000Z","latestAckRevision":null,"latestAckAt":null,"effectConfirmedAt":null,"effectBasis":null}}"""
    }

    fun historyJson(merchantId: String): String =
        """{"items":[{"seq":7,"occurredAt":"2026-10-09T02:00:00.000Z","operation":"RegisterMerchant","aggregateType":"Merchant","aggregateId":"$merchantId","changeType":"MerchantRegistered","actorRef":"operator-1"}],"nextCursor":null}"""
}
