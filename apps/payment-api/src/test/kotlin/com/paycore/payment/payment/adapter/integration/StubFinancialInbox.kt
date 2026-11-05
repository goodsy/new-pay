package com.paycore.payment.payment.adapter.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 시험 전용 FINANCIAL Inbox 대역(실제 HTTP 소켓 — JDK HttpServer, loopback 임의 포트). golden 계약대로 서명(문법·창 300초·kid·HMAC)을 검증하고, 받은 본문을 기록하고,
 * eventId별 대본([script])이 없으면 FINANCIAL처럼 동작한다: 처음 보면 "커밋"하고 APPLIED ACK, 이미 커밋한 eventId면 DUPLICATE ACK(originalOutcome).
 * 대본 동작: 상태 코드만 응답(커밋 없음), 커밋한 뒤 응답 지연(응답 유실 재현 — 호출자 timeout), 임의 ACK 본문.
 */
class StubFinancialInbox : AutoCloseable {

    sealed interface Behavior {
        /** 커밋 없이 [status]와 본문 [body](기본: 대본 표시 오류 — FINANCIAL 계약 코드 아님). */
        data class Status(val status: Int, val body: String = """{"error":"SCRIPTED"}""") : Behavior

        /** [delay] 뒤 FINANCIAL처럼 처리(느린 FINANCIAL — 호출자 timeout 안). */
        data class Delay(val delay: Duration) : Behavior

        /** 커밋(기록)한 뒤 [delay] 동안 응답하지 않는다(그 뒤 정상 ACK — 호출자는 이미 timeout). */
        data class CommitThenStall(val delay: Duration) : Behavior

        /** 커밋한 뒤 [body]를 200으로 응답(ACK 모양 시험). */
        data class CommitThenAck(val body: String) : Behavior
    }

    data class Received(val eventId: String, val effectKey: String, val eventType: String, val body: ByteArray, val signatureValid: Boolean)

    private val mapper = JsonMapper.builder().build()
    private val key: ByteArray = Base64.getDecoder().decode(KEY_BASE64)
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newFixedThreadPool(16)
    private val committed = ConcurrentHashMap<String, String>()
    val received = CopyOnWriteArrayList<Received>()
    private val scripts = ConcurrentHashMap<String, ConcurrentLinkedDeque<Behavior>>()

    init {
        server.executor = pool
        server.createContext(FinancialFactEnvelope.PATH) { exchange -> handle(exchange) }
        server.start()
    }

    val endpoint: URI get() = URI.create("http://127.0.0.1:${server.address.port}${FinancialFactEnvelope.PATH}")

    fun script(eventId: String, vararg behaviors: Behavior) {
        scripts.computeIfAbsent(eventId) { ConcurrentLinkedDeque() }.addAll(behaviors)
    }

    fun receivedFor(eventId: String): List<Received> = received.filter { it.eventId == eventId }

    fun isCommitted(eventId: String): Boolean = committed.containsKey(eventId)

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use { ex ->
            val body = ex.requestBody.readAllBytes()
            val valid = verify(ex.requestHeaders.getFirst(FinancialFactEnvelope.SIGNATURE_HEADER), body)
            val node = mapper.readTree(body)
            val eventId = node.get("eventId").asString()
            val effectKey = node.get("effectKey").asString()
            received += Received(eventId, effectKey, node.get("eventType").asString(), body, valid)
            if (!valid) return respond(ex, 401, """{"error":"UNAUTHENTICATED"}""")
            when (val behavior = scripts[eventId]?.pollFirst()) {
                is Behavior.Status -> respond(ex, behavior.status, behavior.body)
                is Behavior.Delay -> {
                    Thread.sleep(behavior.delay.toMillis())
                    val original = committed.putIfAbsent(eventId, "APPLIED")
                    respond(ex, 200, if (original == null) ack(eventId, effectKey, "APPLIED", null) else ack(eventId, effectKey, "DUPLICATE", original))
                }
                is Behavior.CommitThenStall -> {
                    committed.putIfAbsent(eventId, "APPLIED")
                    Thread.sleep(behavior.delay.toMillis())
                    respond(ex, 200, ack(eventId, effectKey, "APPLIED", null))
                }
                is Behavior.CommitThenAck -> {
                    committed.putIfAbsent(eventId, "APPLIED")
                    respond(ex, 200, behavior.body)
                }
                null -> {
                    val original = committed.putIfAbsent(eventId, "APPLIED")
                    respond(ex, 200, if (original == null) ack(eventId, effectKey, "APPLIED", null) else ack(eventId, effectKey, "DUPLICATE", original))
                }
            }
        }
    }

    private fun verify(header: String?, body: ByteArray): Boolean {
        val m = header?.let { HEADER.matchEntire(it) } ?: return false
        val (t, kid, v1) = m.destructured
        if (kid != KID || kotlin.math.abs(Instant.now().epochSecond - t.toLong()) > WINDOW_SECONDS) return false
        val canonical = listOf(
            "paycore.payment-fact.v1", "POST", "/internal/v1/payment-facts", t, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)),
        ).joinToString("\n")
        val expected = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(expected, HexFormat.of().parseHex(v1))
    }

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        try {
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            ex.responseBody.write(bytes)
        } catch (e: java.io.IOException) {
            // 호출자가 이미 끊었다(응답 유실 시험) — 대역은 무시한다.
        }
    }

    companion object {
        const val KID = "fact-golden-k1"
        const val KEY_BASE64 = "dGVzdC1vbmx5LXBheW1lbnQtZmFjdC1nb2xkZW4ta2V5LTAwMDE="
        private const val WINDOW_SECONDS = 300
        private val HEADER = Regex("^t=([0-9]{1,10}),kid=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})$")

        fun ack(eventId: String, effectKey: String, outcome: String, original: String?, consumer: String = "financial-batch"): String =
            """{"consumer":"$consumer","eventId":"$eventId","effectKey":"$effectKey","outcome":"$outcome"""" +
                (original?.let { ""","originalOutcome":"$it"""" } ?: "") + "}"

        /** 이 대역을 가리키는 dispatcher 설정(시험 Demo 값 — 운영값 아님). */
        fun error(code: String): String = """{"error":"$code"}"""

        fun settings(endpoint: URI, requestTimeout: Duration = Duration.ofSeconds(1)) = FinancialFactRelaySettings(
            endpoint = endpoint, connectTimeout = Duration.ofMillis(500), requestTimeout = requestTimeout, signingKeyId = KID, signingKey = KEY_BASE64, maxAckBytes = 4096,
        )
    }
}
