package com.paycore.simulator.support

import com.paycore.simulator.van.adapter.integration.VanFrameCodec
import com.paycore.simulator.van.adapter.integration.VanFrameException
import com.paycore.simulator.van.adapter.integration.VanFrameFailure
import com.paycore.simulator.van.adapter.integration.VanKeyRing
import com.paycore.simulator.van.domain.VanUlid
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * 시험 전용 PG 쪽 TCP 클라이언트(sim-van-1 — golden 시험 키). 연결 하나로 프레임을 보내고 받는다. 응답 프레임의 MAC은 [VanFrameCodec.read]가 검사한다
 * (불일치 = INTEGRITY 예외). 운영 PG Adapter가 아니다(그것은 payment-api S4).
 */
class VanTestClient(port: Int, private val readTimeoutMillis: Int = 5_000) : Closeable {
    private val socket = Socket().also {
        it.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        it.soTimeout = readTimeoutMillis
    }

    fun send(message: JsonNode, key: ByteArray = KEY, keyId: String = KEY_ID) = sendRaw(VanFrameCodec.encode(keyId, key, MAPPER.writeValueAsBytes(message)))

    fun sendRaw(bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    /** 다음 응답 메시지. 연결이 닫히면 [Closed], 시간 안에 오지 않으면 [TimedOut]. */
    fun receive(): Received = try {
        Received.Message(MAPPER.readTree(VanFrameCodec.read(socket.getInputStream(), RING).payload))
    } catch (e: VanFrameException) {
        if (e.failure == VanFrameFailure.CLOSED) Received.Closed else Received.Broken(e.failure)
    } catch (e: SocketTimeoutException) {
        Received.TimedOut
    } catch (e: java.net.SocketException) {
        Received.Closed
    }

    fun call(message: JsonNode): JsonNode = (receiveAfter(message) as? Received.Message)?.body ?: error("no response message")

    fun receiveAfter(message: JsonNode): Received {
        send(message)
        return receive()
    }

    override fun close() = socket.close()

    sealed interface Received {
        data class Message(val body: JsonNode) : Received
        data object Closed : Received
        data object TimedOut : Received
        data class Broken(val failure: VanFrameFailure) : Received
    }

    companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()
        const val KEY_ID = "sim-van-test-1"
        val KEY: ByteArray = Base64.getDecoder().decode("dGVzdC1vbmx5LXNpbS12YW4taG1hYy1rZXktMDAwMDE=")
        val RING = VanKeyRing(mapOf(KEY_ID to VanKeyRing.Key("pg-demo", KEY)))
        private val seq = AtomicInteger(1000)

        fun messageId(): String = VanUlid.format(Instant.now(), seq.incrementAndGet())

        /** 요청 ID(ULID 시각 = [at] — VAN은 이 시각으로 요청 연령을 본다). */
        fun requestId(at: Instant): String = "ar_" + VanUlid.format(at, seq.incrementAndGet())

        fun inquiryId(): String = "aq_" + VanUlid.format(Instant.now(), seq.incrementAndGet())

        fun authRequest(
            requestId: String,
            evidence: Evidence,
            amountMinor: Long = evidence.amountMinor,
            months: Int? = evidence.months,
            account: String = "SIMMERCH0001",
            environment: String = "TEST",
            requestedAt: String = "2026-10-16T01:00:31.000Z",
        ): ObjectNode = MAPPER.createObjectNode().apply {
            put("type", "AUTH_REQUEST"); put("messageId", messageId()); put("requestId", requestId); put("institutionAccount", account)
            put("merchantRef", "mid-demo-0001"); put("environment", environment); put("routeRef", "SIMVAN01"); put("amountMinor", amountMinor); put("currency", "KRW")
            putObject("plan").apply { if (months == null) put("kind", "LUMP_SUM") else { put("kind", "INSTALLMENT"); put("months", months) } }
            putObject("authentication").apply {
                put("evidenceRef", evidence.evidenceRef); put("providerCode", evidence.providerCode); put("issuerCode", evidence.issuerCode); put("acquirerCode", evidence.acquirerCode)
            }
            put("requestedAt", requestedAt)
        }

        fun inquiry(originalRequestId: String, closeIfAbsent: Boolean = false, account: String = "SIMMERCH0001"): ObjectNode = MAPPER.createObjectNode().apply {
            put("type", "INQUIRY_REQUEST"); put("messageId", messageId()); put("inquiryId", inquiryId()); put("originalRequestId", originalRequestId)
            put("institutionAccount", account); put("environment", "TEST"); put("closeIfAbsent", closeIfAbsent)
        }

        /** sim-van-2 취소 요청 ID(ULID 시각 = [at] — VAN은 이 시각으로 취소 요청 연령을 본다, CANCEL-001 §6.5). */
        fun cancelRequestId(at: Instant): String = "cr_" + VanUlid.format(at, seq.incrementAndGet())

        fun cancelInquiryId(): String = "cq_" + VanUlid.format(Instant.now(), seq.incrementAndGet())

        /** sim-van-2 CANCEL_REQUEST(golden 필드 순서). 원 승인 echo는 [original]의 AUTH_RESPONSE 값. */
        @Suppress("LongParameterList")
        fun cancelRequest(
            requestId: String,
            originalRequestId: String,
            original: JsonNode,
            amountMinor: Long,
            scope: String,
            account: String = "SIMMERCH0001",
            requestedAt: String = "2026-10-16T01:00:31.000Z",
        ): ObjectNode = MAPPER.createObjectNode().apply {
            put("type", "CANCEL_REQUEST"); put("messageId", messageId()); put("requestId", requestId); put("institutionAccount", account)
            put("merchantRef", "mid-demo-0001"); put("environment", "TEST"); put("routeRef", "SIMVAN01"); put("originalRequestId", originalRequestId)
            put("originalProviderTransactionId", original.get("providerTransactionId").stringValue()); put("originalApprovalNumber", original.get("approvalNumber").stringValue())
            put("originalTransactionDate", original.get("institutionTransactionDate").stringValue()); put("cancelAmountMinor", amountMinor); put("currency", "KRW")
            put("scope", scope); put("requestedAt", requestedAt)
        }

        fun cancelInquiry(originalRequestId: String, closeIfAbsent: Boolean = false, account: String = "SIMMERCH0001"): ObjectNode = MAPPER.createObjectNode().apply {
            put("type", "CANCEL_INQUIRY_REQUEST"); put("messageId", messageId()); put("inquiryId", cancelInquiryId()); put("originalRequestId", originalRequestId)
            put("institutionAccount", account); put("environment", "TEST"); put("closeIfAbsent", closeIfAbsent)
        }
    }

    /** 가상 인증기관에서 받은 인증 증거(결과 조회 본문에서). */
    data class Evidence(
        val evidenceRef: String, val providerCode: String, val issuerCode: String, val acquirerCode: String, val cardType: String, val amountMinor: Long, val months: Int?,
    )
}
