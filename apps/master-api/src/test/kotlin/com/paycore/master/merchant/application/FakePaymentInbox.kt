package com.paycore.master.merchant.application

import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 시험 전용 가짜 PAYMENT 내부 Inbox(JDK HttpServer, 127.0.0.1 임의 포트). 실제 HTTP Dispatcher(RestClient, timeout·서명 포함)를 그대로 시험하려고
 * 쓴다. 받은 요청(본문 바이트·서명 헤더)을 기록하고, 이벤트마다 정한 응답을 돌려준다. 기본 응답은 내구 커밋을 흉내 낸 200
 * `{consumer, outcome: APPLIED, appliedRevision: aggregateVersion}`이며 같은 eventId 재전송은 DUPLICATE다(PAYMENT Inbox와 같은 의미).
 * 운영 코드가 아니다. PAYMENT 앱 코드에 의존하지 않는다(앱 간 직접 의존 금지 — 계약 일치는 golden 시험).
 */
class FakePaymentInbox : AutoCloseable {
    data class Received(val eventId: String, val aggregateId: String, val aggregateVersion: Long, val body: ByteArray, val signature: String?)

    /** 한 요청에 대한 응답 계획. [delayMillis]만큼 늦게 응답한다(read timeout 주입 — 그래도 "커밋"은 이미 된 것으로 기록). */
    data class Reply(
        val status: Int,
        val outcome: String? = null,
        val appliedRevision: Long? = null,
        val delayMillis: Long = 0,
        val originalOutcome: String? = null,
    )

    private val mapper = JsonMapper.builder().build()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val scripted = ConcurrentHashMap<String, ArrayDeque<Reply>>()
    private val committed = ConcurrentHashMap<String, String>()
    val received: MutableList<Received> = CopyOnWriteArrayList()

    val endpoint: String get() = "http://127.0.0.1:${server.address.port}/internal/v1/entitlement-snapshots"

    init {
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/internal/v1/entitlement-snapshots") { exchange ->
            val body = exchange.requestBody.use { it.readAllBytes() }
            val node = mapper.readTree(body)
            val eventId = node.get("eventId").stringValue()
            val revision = node.get("aggregateVersion").longValue()
            received += Received(eventId, node.get("aggregateId").stringValue(), revision, body, exchange.requestHeaders.getFirst("PayCore-Signature"))
            val plan = synchronized(scripted) { scripted[eventId]?.removeFirstOrNull() } ?: Reply(200)
            val reply = if (plan.status == 200 && plan.outcome == null) {
                val first = committed.putIfAbsent(eventId, "APPLIED")
                if (first == null) {
                    plan.copy(outcome = "APPLIED", appliedRevision = plan.appliedRevision ?: revision)
                } else {
                    plan.copy(outcome = "DUPLICATE", appliedRevision = plan.appliedRevision ?: revision, originalOutcome = first)
                }
            } else {
                if (plan.status == 200 && plan.outcome != null && plan.outcome != "DUPLICATE") committed.putIfAbsent(eventId, plan.outcome)
                plan
            }
            if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
            val bytes = if (reply.outcome != null) {
                mapper.writeValueAsBytes(
                    mapOf("consumer" to "payment-api", "outcome" to reply.outcome, "appliedRevision" to reply.appliedRevision) +
                        (reply.originalOutcome?.let { mapOf("originalOutcome" to it) } ?: emptyMap()),
                )
            } else {
                "{}".toByteArray()
            }
            runCatching {
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
    }

    /** [eventId]의 다음 응답들을 순서대로 정한다(소진되면 기본 응답). */
    fun script(eventId: String, vararg replies: Reply) {
        synchronized(scripted) { scripted.getOrPut(eventId) { ArrayDeque() }.addAll(replies) }
    }

    fun receivedFor(aggregateId: String): List<Received> = received.filter { it.aggregateId == aggregateId }

    override fun close() {
        server.stop(0)
    }
}
