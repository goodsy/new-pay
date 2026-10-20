package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.application.required.InquiryUnavailableReason
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.domain.UnknownCause
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/**
 * 가상 VAN 연결·교환(AUTH-001 §5.7 — 승인 Adapter에서 그대로 옮김, CANCEL-001 §6.6 취소 Adapter와 공유. 동작 변경 없음):
 * - [connect]: 첫 시도 + Route 재시도 예산. 시도마다 deadline을 먼저 본다(지났으면 연결하지 않는다). 연결 못 하면 null(아무것도 보내지 않았다).
 * - [exchange]: 요청 프레임을 쓰고 응답 프레임 하나를 읽는다. 전체 deadline(단조 시계)은 쓰기와 읽기 전체에 적용된다: 매 read() 전에 소켓 timeout을 `min(readTimeout,
 *   남은 deadline)`으로 다시 정하고, watchdog가 deadline에 소켓을 닫아 막힌 쓰기·읽기도 끝낸다. deadline 경과는 TIMEOUT, 그 밖의 끊김은 CONNECTION_LOST.
 */
internal class SimVanTransport(private val clock: PaymentClock, private val connector: VanSocketConnector) {

    fun connect(route: ConfiguredInstitutionRouteRegistry.Route, deadline: Instant): Socket? {
        val address = InetSocketAddress(route.host, route.port)
        repeat(route.connectRetryBudget + 1) {
            val remaining = Duration.between(clock.now(), deadline)
            if (remaining.isNegative || remaining.isZero) return null
            val timeout = minOf(route.connectTimeout, remaining).toMillis().coerceAtLeast(1).toInt()
            try {
                return connector.connect(address, timeout)
            } catch (e: IOException) {
                // 연결 실패 — 바이트 미송신. 같은 요청 ID로 다시 연결한다.
            }
        }
        return null
    }

    fun <T> exchange(socket: Socket, route: ConfiguredInstitutionRouteRegistry.Route, payload: ByteArray, parse: (ByteArray) -> T): Pair<T, ByteArray> {
        val deadline = System.nanoTime() + route.totalDeadline.toNanos()
        fun remainingNanos() = deadline - System.nanoTime()
        val watchdog = Thread.ofVirtual().name("van-call-deadline").start {
            try {
                Thread.sleep(Duration.ofNanos(remainingNanos().coerceAtLeast(0)))
                runCatching { socket.close() }
            } catch (e: InterruptedException) {
                // 호출이 deadline 전에 끝났다.
            }
        }
        try {
            try {
                socket.getOutputStream().write(SimVanFrames.encode(route.keyId, route.key, payload))
                socket.getOutputStream().flush()
            } catch (e: IOException) {
                throw SimVanProtocolException(if (remainingNanos() <= 0) UnknownCause.TIMEOUT else UnknownCause.CONNECTION_LOST)
            }
            val raw = try {
                SimVanFrames.read(socket.getInputStream(), route.keyId, route.key) {
                    val remaining = remainingNanos()
                    if (remaining <= 0) throw SimVanProtocolException(UnknownCause.TIMEOUT)
                    try {
                        socket.soTimeout = minOf(route.readTimeout.toNanos(), remaining).let { Duration.ofNanos(it).toMillis() }.coerceAtLeast(1).toInt()
                    } catch (e: IOException) {
                        throw SimVanProtocolException(if (remainingNanos() <= 0) UnknownCause.TIMEOUT else UnknownCause.CONNECTION_LOST)
                    }
                }
            } catch (e: SimVanProtocolException) {
                // watchdog가 닫은 소켓의 읽기 실패는 deadline 경과다.
                if (e.unknownCause == UnknownCause.CONNECTION_LOST && remainingNanos() <= 0) throw SimVanProtocolException(UnknownCause.TIMEOUT)
                throw e
            }
            return parse(raw) to raw
        } finally {
            watchdog.interrupt()
        }
    }

    companion object {
        /** 응답 증거(payload SHA-256 참조만 — 원문은 남기지 않는다). */
        fun evidenceHash(payload: ByteArray): String = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))

        /** 조회 교환 실패 → 조회 불가 사유. */
        fun unavailableReason(cause: UnknownCause): InquiryUnavailableReason = when (cause) {
            UnknownCause.TIMEOUT -> InquiryUnavailableReason.TIMEOUT
            UnknownCause.FRAMING -> InquiryUnavailableReason.FRAMING
            UnknownCause.INTEGRITY -> InquiryUnavailableReason.INTEGRITY
            UnknownCause.CORRELATION -> InquiryUnavailableReason.CORRELATION
            else -> InquiryUnavailableReason.CONNECTION
        }
    }
}
