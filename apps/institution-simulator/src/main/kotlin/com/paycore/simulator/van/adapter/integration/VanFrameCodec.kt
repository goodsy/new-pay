package com.paycore.simulator.van.adapter.integration

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 프레임 계약 위반 종류(AUTH-001 §5.2): CLOSED = 프레임 경계에서 스트림 끝, FRAMING = 길이·버전·잘림·JSON 스키마, INTEGRITY = MAC 불일치·모르는 키. */
enum class VanFrameFailure { CLOSED, FRAMING, INTEGRITY }

class VanFrameException(val failure: VanFrameFailure) : RuntimeException(failure.name, null, false, false)

/** 검증된 프레임 하나(keyId가 정한 PG client와 payload 원문 바이트). */
class VanFrame(val keyId: String, val clientId: String, val payload: ByteArray)

/**
 * sim-van-1 프레임(AUTH-001 §5.2): `length:u32be | version:u8(=1) | keyIdLen:u8 | keyId | mac:32 | payload`, length = 이후 바이트 수(1..16 KiB),
 * mac = HMAC-SHA256(key, version ‖ keyIdLen ‖ keyId ‖ payload). 읽기는 길이까지 누적한다 — 한 번의 read를 프레임 하나로 가정하지 않는다(부분 read·한 read에 프레임 둘).
 * MAC은 상수 시간 비교. 이 codec은 PG Adapter(payment-api)와 공유하지 않는다 — 계약은 golden 파일로만 공유한다.
 */
object VanFrameCodec {
    const val VERSION = 1
    const val MAX_LENGTH = 16 * 1024
    private const val MAC_BYTES = 32
    private const val ALGORITHM = "HmacSHA256"

    fun encode(keyId: String, key: ByteArray, payload: ByteArray): ByteArray {
        val kid = keyId.toByteArray(Charsets.US_ASCII)
        require(kid.size in 1..255) { "key id must be 1..255 bytes" }
        val head = byteArrayOf(VERSION.toByte(), kid.size.toByte()) + kid
        val body = head + mac(key, head, payload) + payload
        require(body.size <= MAX_LENGTH) { "frame too long" }
        return ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
    }

    /** MAC을 일부러 틀리게 만든 프레임(시나리오 VC-VAN-BAD-MAC 전용). */
    fun encodeWithBrokenMac(keyId: String, key: ByteArray, payload: ByteArray): ByteArray = encode(keyId, key, payload).also { frame ->
        val macStart = 4 + 2 + keyId.length
        frame[macStart] = (frame[macStart].toInt() xor 0x01).toByte()
    }

    fun read(input: InputStream, keys: VanKeyRing): VanFrame {
        val lengthBytes = readFully(input, 4, atBoundary = true)
        val length = ByteBuffer.wrap(lengthBytes).int
        if (length < 2 + 1 + MAC_BYTES + 1 || length > MAX_LENGTH) throw VanFrameException(VanFrameFailure.FRAMING)
        val body = readFully(input, length, atBoundary = false)
        if (body[0].toInt() != VERSION) throw VanFrameException(VanFrameFailure.FRAMING)
        val kidLen = body[1].toInt() and 0xff
        val payloadStart = 2 + kidLen + MAC_BYTES
        if (kidLen == 0 || payloadStart >= body.size) throw VanFrameException(VanFrameFailure.FRAMING)
        val keyId = String(body, 2, kidLen, Charsets.US_ASCII)
        val key = keys.find(keyId) ?: throw VanFrameException(VanFrameFailure.INTEGRITY)
        val head = body.copyOfRange(0, 2 + kidLen)
        val received = body.copyOfRange(2 + kidLen, payloadStart)
        val payload = body.copyOfRange(payloadStart, body.size)
        if (!MessageDigest.isEqual(received, mac(key.secret, head, payload))) throw VanFrameException(VanFrameFailure.INTEGRITY)
        return VanFrame(keyId, key.clientId, payload)
    }

    private fun mac(key: ByteArray, head: ByteArray, payload: ByteArray): ByteArray =
        Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }.run {
            update(head)
            doFinal(payload)
        }

    /** [n]바이트를 누적해서 읽는다. 첫 바이트 전에 스트림이 끝나면(프레임 경계) CLOSED, 중간에 끝나면 FRAMING(잘린 프레임). */
    private fun readFully(input: InputStream, n: Int, atBoundary: Boolean): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = try {
                input.read(out, read, n - read)
            } catch (e: EOFException) {
                -1
            }
            if (r < 0) throw VanFrameException(if (atBoundary && read == 0) VanFrameFailure.CLOSED else VanFrameFailure.FRAMING)
            read += r
        }
        return out
    }
}
