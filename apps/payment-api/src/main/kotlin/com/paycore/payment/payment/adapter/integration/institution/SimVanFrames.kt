package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.domain.UnknownCause
import java.io.EOFException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 프레임·메시지 계약 위반(→ OutcomeUnknown cause, AUTH-001 §5.2). */
internal class SimVanProtocolException(val unknownCause: UnknownCause) : RuntimeException(unknownCause.name, null, false, false)

/**
 * PG 쪽 sim-van-1 프레임 codec(AUTH-001 §5.2 — Simulator 코드와 공유하지 않는다, 계약은 golden 파일로만): `length:u32be | version:u8(=1) | keyIdLen:u8 | keyId |
 * mac:32 | payload`. 읽기는 길이까지 누적한다(부분 read). 길이·버전·잘림 → FRAMING, MAC·키 → INTEGRITY, 끝 → CONNECTION_LOST, 소켓 timeout → TIMEOUT.
 */
internal object SimVanFrames {
    private const val VERSION = 1
    private const val MAX_LENGTH = 16 * 1024
    private const val MAC_BYTES = 32
    private const val ALGORITHM = "HmacSHA256"

    fun encode(keyId: String, key: ByteArray, payload: ByteArray): ByteArray {
        val kid = keyId.toByteArray(Charsets.US_ASCII)
        val head = byteArrayOf(VERSION.toByte(), kid.size.toByte()) + kid
        val body = head + mac(key, head, payload) + payload
        require(body.size <= MAX_LENGTH) { "frame too long" }
        return ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
    }

    /**
     * 응답 프레임 하나의 payload. [keyId]·[key]는 이 Route의 키 — 다른 keyId 프레임은 INTEGRITY. [beforeRead]는 매 read() 전에 불린다: 호출자가 남은 전체 deadline으로
     * 소켓 timeout을 다시 정하고, deadline이 지났으면 TIMEOUT을 던진다(검토 #2 m-A — 조금씩 흘리는 상대도 전체 deadline에서 끝난다).
     */
    fun read(input: InputStream, keyId: String, key: ByteArray, beforeRead: () -> Unit = {}): ByteArray {
        val length = ByteBuffer.wrap(readFully(input, 4, beforeRead)).int
        if (length < 2 + 1 + MAC_BYTES + 1 || length > MAX_LENGTH) throw SimVanProtocolException(UnknownCause.FRAMING)
        val body = readFully(input, length, beforeRead)
        if (body[0].toInt() != VERSION) throw SimVanProtocolException(UnknownCause.FRAMING)
        val kidLen = body[1].toInt() and 0xff
        val payloadStart = 2 + kidLen + MAC_BYTES
        if (kidLen == 0 || payloadStart >= body.size) throw SimVanProtocolException(UnknownCause.FRAMING)
        if (String(body, 2, kidLen, Charsets.US_ASCII) != keyId) throw SimVanProtocolException(UnknownCause.INTEGRITY)
        val head = body.copyOfRange(0, 2 + kidLen)
        val payload = body.copyOfRange(payloadStart, body.size)
        if (!MessageDigest.isEqual(body.copyOfRange(2 + kidLen, payloadStart), mac(key, head, payload))) throw SimVanProtocolException(UnknownCause.INTEGRITY)
        return payload
    }

    private fun mac(key: ByteArray, head: ByteArray, payload: ByteArray): ByteArray =
        Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }.run {
            update(head)
            doFinal(payload)
        }

    private fun readFully(input: InputStream, n: Int, beforeRead: () -> Unit): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            beforeRead()
            val r = try {
                input.read(out, read, n - read)
            } catch (e: SocketTimeoutException) {
                throw SimVanProtocolException(UnknownCause.TIMEOUT)
            } catch (e: EOFException) {
                -1
            } catch (e: java.io.IOException) {
                throw SimVanProtocolException(UnknownCause.CONNECTION_LOST)
            }
            if (r < 0) throw SimVanProtocolException(UnknownCause.CONNECTION_LOST)
            read += r
        }
        return out
    }
}
