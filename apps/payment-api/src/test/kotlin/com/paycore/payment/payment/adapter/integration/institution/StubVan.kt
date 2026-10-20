package com.paycore.payment.payment.adapter.integration.institution

import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 시험 전용 TCP stub VAN(AUTH-001 §8 — payment-api 시험은 Simulator 앱에 의존하지 않는다). golden `van-contract-sim-van-1`의 시험 키로 프레임을 읽고, 시험이 정한
 * [Action]대로 응답한다(분할 write·지연·MAC 오류·응답 없이 끊기·무응답·두 번 응답). 프레임 처리는 운영 Adapter 코드와 독립으로 여기서 다시 구현한다(순환 검증 방지).
 * 원장 의미(같은 ID 기존 결정·종결 표지)는 S6·S7 시험이 이 stub 위에 메모리로 더한다.
 */
class StubVan(private val handler: (JsonNode) -> Action) : Closeable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val received = CopyOnWriteArrayList<JsonNode>()
    val receivedFrames = CopyOnWriteArrayList<ByteArray>()
    val connections = AtomicInteger()

    init {
        Thread.ofVirtual().name("stub-van").start {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                Thread.ofVirtual().start { serve(s) }
            }
        }
    }

    sealed interface Action {
        /** [payload]를 프레임으로 응답한다. [chunk] 바이트씩 [chunkDelayMillis] 간격으로 나눠 쓰고, [times]번 반복한다. */
        data class Respond(
            val payload: ByteArray,
            val delayMillis: Long = 0,
            val chunk: Int = Int.MAX_VALUE,
            val chunkDelayMillis: Long = 0,
            val brokenMac: Boolean = false,
            val key: ByteArray = KEY,
            val keyId: String = KEY_ID,
            val times: Int = 1,
        ) : Action

        /** 응답 없이 연결을 닫는다. */
        data object Close : Action

        /** 응답하지 않고 [millis] 동안 연결을 연다. */
        data class Silent(val millis: Long) : Action
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            val input = DataInputStream(s.getInputStream())
            val length = try {
                input.readInt()
            } catch (e: EOFException) {
                return
            }
            val body = ByteArray(length).also { input.readFully(it) }
            receivedFrames += ByteBuffer.allocate(4 + length).putInt(length).put(body).array()
            val kidLen = body[1].toInt() and 0xff
            val payload = body.copyOfRange(2 + kidLen + 32, body.size)
            val request = MAPPER.readTree(payload)
            received.add(request)
            when (val a = handler(request)) {
                Action.Close -> return
                is Action.Silent -> Thread.sleep(a.millis)
                is Action.Respond -> {
                    if (a.delayMillis > 0) Thread.sleep(a.delayMillis)
                    repeat(a.times) { write(s.getOutputStream(), frame(a.keyId, a.key, a.payload, a.brokenMac), a.chunk, a.chunkDelayMillis) }
                    Thread.sleep(200)
                }
            }
        }
    }

    private fun write(out: OutputStream, bytes: ByteArray, chunk: Int, delay: Long) = runCatching {
        bytes.asList().chunked(chunk).forEach {
            out.write(it.toByteArray())
            out.flush()
            if (delay > 0) Thread.sleep(delay)
        }
    }

    override fun close() = server.close()

    companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()
        val GOLDEN: JsonNode = MAPPER.readTree(ClassPathResource("contracts/van-contract-sim-van-1.golden.json").inputStream)
        val KEY_ID: String = GOLDEN.get("hmac").get("keyId").stringValue()
        val KEY: ByteArray = Base64.getDecoder().decode(GOLDEN.get("hmac").get("keyBase64").stringValue())

        fun golden(name: String): ObjectNode = GOLDEN.get("messages").get(name).deepCopy() as ObjectNode

        /** sim-van-2 golden(CANCEL-001 §6.1 — sim-van-1 전부 + 취소 4종, 같은 시험 키). */
        val GOLDEN_V2: JsonNode = MAPPER.readTree(ClassPathResource("contracts/van-contract-sim-van-2.golden.json").inputStream)

        fun goldenV2(name: String): ObjectNode = GOLDEN_V2.get("messages").get(name).deepCopy() as ObjectNode

        /** 취소 요청에 맞춘 sim-van-2 golden 응답(inReplyTo·requestId/inquiryId·originalRequestId echo). */
        fun replyV2(name: String, request: JsonNode): ObjectNode = goldenV2(name).also { r ->
            r.put("inReplyTo", request.get("messageId").stringValue())
            if (r.has("requestId")) r.put("requestId", request.get("requestId").stringValue())
            if (r.has("inquiryId")) r.put("inquiryId", request.get("inquiryId").stringValue())
            if (r.has("originalRequestId")) r.put("originalRequestId", request.get("originalRequestId").stringValue())
        }

        fun bytes(node: JsonNode): ByteArray = MAPPER.writeValueAsBytes(node)

        fun frame(keyId: String, key: ByteArray, payload: ByteArray, brokenMac: Boolean = false): ByteArray {
            val kid = keyId.toByteArray(Charsets.US_ASCII)
            val head = byteArrayOf(1, kid.size.toByte()) + kid
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.run { update(head); doFinal(payload) }
            if (brokenMac) mac[0] = (mac[0].toInt() xor 1).toByte()
            val body = head + mac + payload
            return ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
        }

        /** 요청에 맞춘 golden 응답(inReplyTo·requestId/inquiryId·originalRequestId를 요청에서 echo). */
        fun reply(name: String, request: JsonNode): ObjectNode = golden(name).also { r ->
            r.put("inReplyTo", request.get("messageId").stringValue())
            if (r.has("requestId")) r.put("requestId", request.get("requestId").stringValue())
            if (r.has("inquiryId")) r.put("inquiryId", request.get("inquiryId").stringValue())
            if (r.has("originalRequestId")) r.put("originalRequestId", request.get("originalRequestId").stringValue())
        }
    }
}
