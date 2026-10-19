package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.application.required.AuthorizationCall
import com.paycore.payment.payment.application.required.AuthorizationCallResult
import com.paycore.payment.payment.application.required.AuthorizationInquiryCall
import com.paycore.payment.payment.application.required.AuthorizationInquiryPort
import com.paycore.payment.payment.application.required.AuthorizationPort
import com.paycore.payment.payment.application.required.InquiryCallResult
import com.paycore.payment.payment.application.required.InquiryUnavailableReason
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.InstitutionOutcome
import com.paycore.payment.payment.domain.ObservationSource
import com.paycore.payment.payment.domain.UnknownCause
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/**
 * 가상 VAN TCP Adapter(AUTH-001 §5.7 — sim-van-1). 요청당 연결 하나(AU-8). 시간은 connect / read / 전체 deadline으로 나눈다(Route 설정, Demo).
 * - connect 단계 실패(한 바이트도 보내지 않음)만 같은 요청 ID로 Route 예산 안에서 재연결하고, 재연결마다 호출자의 connect deadline을 다시 본다(AU-34). 소진·경과 → NotSentConfirmed.
 * - 요청을 쓰기 시작한 뒤의 모든 실패는 OutcomeUnknown(TIMEOUT·CONNECTION_LOST·FRAMING·CORRELATION·INTEGRITY) — 금융효과 없음으로 단정하지 않는다(AC1 §5.2).
 * - 응답은 관찰로 바꿔 돌려줄 뿐 domain 전이를 부르지 않는다(ArchUnit `institutionAdapterDoesNotMutateDomain`). sourceResultId: 결정 `decisionId`, PENDING `pendingId`(H-3),
 *   NOT_FOUND `inquiryId`, 무효과 종결 `closureId`(§5.7.3). 증거는 payload SHA-256만 보존하고 원문은 로그에 쓰지 않는다.
 * - 지표(§9 Observability, S8): counter `paycore.authorization.dispatch{outcome=APPROVED·DECLINED·PENDING·UNKNOWN·NOT_SENT·SENDER_FAILED}`,
 *   `paycore.authorization.inquiry{result=FOUND·PENDING·NOT_FOUND·NO_EFFECT_CONFIRMED·UNAVAILABLE·SENDER_FAILED}`, timer `paycore.authorization.call.duration{operation=AUTHORIZE·INQUIRY}`
 *   (연결 시도 포함 호출 전체 — 예외로 끝난 호출도 기록하고 SENDER_FAILED로 센다). label에 요청·Attempt·조회 ID, 기관 계정, Route 이름을 넣지 않는다.
 * - 모르는 routeRef는 연결하지 않는다(fail-closed). 조회의 무효과 종결 요청은 Capability `confirmsNoEffect` Route에만 보내고, 그 밖 Route의 NO_EFFECT_CONFIRMED는 INTEGRITY다.
 */
@Component
class SimulatorVanAuthorizationAdapter internal constructor(
    private val routes: ConfiguredInstitutionRouteRegistry,
    private val clock: PaymentClock,
    private val connector: VanSocketConnector,
    private val registry: MeterRegistry,
    private val messageIds: () -> String,
) : AuthorizationPort, AuthorizationInquiryPort {

    @Autowired
    constructor(routes: ConfiguredInstitutionRouteRegistry, clock: PaymentClock, registry: MeterRegistry) :
        this(routes, clock, VanSocketConnector.PLAIN, registry, UlidMessageIds(clock)::next)

    /** 승인 호출 1회: 호출 시간 timer + 결과 counter(§9 Observability — label은 닫힌 값 집합, ID 없음). */
    override fun authorize(call: AuthorizationCall): AuthorizationCallResult {
        val sample = Timer.start(registry)
        var outcome = SENDER_FAILED // 최종 검토 n-5: socket.use 밖 예상 밖 예외도 timer·counter를 남긴다(예외는 그대로 전파)
        try {
            val result = authorizeOnce(call)
            outcome = when (result) {
                is AuthorizationCallResult.Approved -> "APPROVED"
                is AuthorizationCallResult.Declined -> "DECLINED"
                is AuthorizationCallResult.Pending -> "PENDING"
                is AuthorizationCallResult.OutcomeUnknown -> "UNKNOWN"
                AuthorizationCallResult.NotSentConfirmed -> "NOT_SENT"
            }
            return result
        } finally {
            sample.stop(registry.timer(CALL_DURATION_METRIC, "operation", "AUTHORIZE"))
            registry.counter(DISPATCH_METRIC, "outcome", outcome).increment()
        }
    }

    /** 조회 1회: 호출 시간 timer + 결과 counter(§9 Observability). */
    override fun inquire(call: AuthorizationInquiryCall): InquiryCallResult {
        val sample = Timer.start(registry)
        var outcome = SENDER_FAILED // 최종 검토 n-5: 승인 호출과 같은 닫힌 값
        try {
            val result = inquireOnce(call)
            outcome = when (result) {
                is InquiryCallResult.Found -> if (result.observation.outcome == InstitutionOutcome.PENDING) "PENDING" else "FOUND"
                is InquiryCallResult.NotFound -> "NOT_FOUND"
                is InquiryCallResult.NoEffectConfirmed -> "NO_EFFECT_CONFIRMED"
                is InquiryCallResult.Unavailable -> "UNAVAILABLE"
            }
            return result
        } finally {
            sample.stop(registry.timer(CALL_DURATION_METRIC, "operation", "INQUIRY"))
            registry.counter(INQUIRY_METRIC, "result", outcome).increment()
        }
    }

    private fun authorizeOnce(call: AuthorizationCall): AuthorizationCallResult {
        // 검토 #2 m-B: Route 환경과 요청 환경이 다르면 연결하지 않는다.
        val route = routes.connection(call.routeRef)?.takeIf { it.environment == call.providerRequest.environment.name }
            ?: return AuthorizationCallResult.NotSentConfirmed
        val messageId = messageIds()
        val payload = SimVanMessages.authRequest(call, messageId, clock.now())
        val socket = connect(route, call.connectDeadline) ?: return AuthorizationCallResult.NotSentConfirmed
        return socket.use { s ->
            try {
                val reply = exchange(s, route, payload, SimVanMessages::parseAuthReply)
                val parsed = reply.first
                if (parsed.inReplyTo != messageId || parsed.requestId != call.providerRequest.requestId) throw SimVanProtocolException(UnknownCause.CORRELATION)
                val hash = hash(reply.second)
                when (parsed) {
                    is SimVanMessages.AuthReply.Decided -> if (parsed.terms != null) {
                        AuthorizationCallResult.Approved(observation(call.attemptId, ObservationSource.RESPONSE, parsed.decisionId, InstitutionOutcome.APPROVED, parsed.terms, hash))
                    } else {
                        AuthorizationCallResult.Declined(observation(call.attemptId, ObservationSource.RESPONSE, parsed.decisionId, InstitutionOutcome.DECLINED, null, hash))
                    }
                    is SimVanMessages.AuthReply.Pending ->
                        AuthorizationCallResult.Pending(observation(call.attemptId, ObservationSource.RESPONSE, parsed.pendingId, InstitutionOutcome.PENDING, null, hash))
                    // 기관 기록과 PG payload가 다르거나 종결됐다 — 계약 위반. 원 결정은 조회로 확인한다.
                    is SimVanMessages.AuthReply.Refused -> throw SimVanProtocolException(UnknownCause.CORRELATION)
                }
            } catch (e: SimVanProtocolException) {
                AuthorizationCallResult.OutcomeUnknown(e.unknownCause)
            } catch (e: RuntimeException) {
                // 검토 #2 n-5: 쓰기를 시작한 뒤의 예상 밖 실패도 금융효과 없음으로 단정하지 않는다.
                AuthorizationCallResult.OutcomeUnknown(UnknownCause.CONNECTION_LOST)
            }
        }
    }

    private fun inquireOnce(call: AuthorizationInquiryCall): InquiryCallResult {
        val route = routes.connection(call.routeRef)?.takeIf { it.environment == call.providerRequest.environment.name }
            ?: return InquiryCallResult.Unavailable(InquiryUnavailableReason.UNKNOWN_ROUTE)
        val confirmsNoEffect = route.capabilities.confirmsNoEffect
        val messageId = messageIds()
        val payload = SimVanMessages.inquiryRequest(call, messageId, call.closeIfAbsent && confirmsNoEffect)
        val deadline = clock.now().plus(route.totalDeadline)
        val socket = connect(route, deadline) ?: return InquiryCallResult.Unavailable(InquiryUnavailableReason.CONNECTION)
        return socket.use { s ->
            try {
                val (parsed, raw) = exchange(s, route, payload, SimVanMessages::parseInquiryReply)
                if (parsed.inReplyTo != messageId || parsed.inquiryId != call.inquiryId || parsed.originalRequestId != call.providerRequest.requestId) {
                    throw SimVanProtocolException(UnknownCause.CORRELATION)
                }
                val hash = hash(raw)
                when (parsed) {
                    is SimVanMessages.InquiryReply.Found -> InquiryCallResult.Found(
                        observation(
                            call.attemptId, ObservationSource.INQUIRY, parsed.decisionId,
                            if (parsed.terms != null) InstitutionOutcome.APPROVED else InstitutionOutcome.DECLINED, parsed.terms, hash,
                        ),
                    )
                    is SimVanMessages.InquiryReply.Pending ->
                        InquiryCallResult.Found(observation(call.attemptId, ObservationSource.INQUIRY, parsed.pendingId, InstitutionOutcome.PENDING, null, hash))
                    is SimVanMessages.InquiryReply.NotFound ->
                        InquiryCallResult.NotFound(observation(call.attemptId, ObservationSource.INQUIRY, parsed.inquiryId, InstitutionOutcome.NOT_FOUND, null, hash))
                    is SimVanMessages.InquiryReply.NoEffect -> if (confirmsNoEffect) {
                        InquiryCallResult.NoEffectConfirmed(
                            observation(call.attemptId, ObservationSource.INQUIRY, parsed.closureId, InstitutionOutcome.NO_EFFECT_CONFIRMED, null, hash),
                        )
                    } else {
                        InquiryCallResult.Unavailable(InquiryUnavailableReason.INTEGRITY)
                    }
                    is SimVanMessages.InquiryReply.Unavailable -> InquiryCallResult.Unavailable(InquiryUnavailableReason.INSTITUTION_UNAVAILABLE)
                }
            } catch (e: SimVanProtocolException) {
                InquiryCallResult.Unavailable(
                    when (e.unknownCause) {
                        UnknownCause.TIMEOUT -> InquiryUnavailableReason.TIMEOUT
                        UnknownCause.FRAMING -> InquiryUnavailableReason.FRAMING
                        UnknownCause.INTEGRITY -> InquiryUnavailableReason.INTEGRITY
                        UnknownCause.CORRELATION -> InquiryUnavailableReason.CORRELATION
                        else -> InquiryUnavailableReason.CONNECTION
                    },
                )
            } catch (e: RuntimeException) {
                InquiryCallResult.Unavailable(InquiryUnavailableReason.CONNECTION)
            }
        }
    }

    /** connect 단계: 첫 시도 + 재시도 예산. 시도마다 [deadline]을 먼저 본다(지났으면 연결하지 않는다). 연결 못 하면 null(아무것도 보내지 않았다). */
    private fun connect(route: ConfiguredInstitutionRouteRegistry.Route, deadline: Instant): Socket? {
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

    /**
     * 요청 프레임을 쓰고 응답 프레임 하나를 읽는다. 전체 deadline(단조 시계)은 쓰기와 읽기 전체에 적용된다(검토 #2 m-A·n-4): 매 read() 전에 소켓 timeout을
     * `min(readTimeout, 남은 deadline)`으로 다시 정하고, watchdog가 deadline에 소켓을 닫아 막힌 쓰기·읽기도 끝낸다. deadline 경과는 TIMEOUT, 그 밖의 끊김은 CONNECTION_LOST.
     */
    private fun <T> exchange(socket: Socket, route: ConfiguredInstitutionRouteRegistry.Route, payload: ByteArray, parse: (ByteArray) -> T): Pair<T, ByteArray> {
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

    private fun observation(
        attemptId: AttemptId, source: ObservationSource, sourceResultId: String, outcome: InstitutionOutcome, terms: ApprovedTerms?, hash: String,
    ): InstitutionObservation = InstitutionObservation(attemptId, source, sourceResultId, outcome, terms, hash, clock.now())

    private fun hash(payload: ByteArray): String = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))

    private companion object {
        const val DISPATCH_METRIC = "paycore.authorization.dispatch"
        const val INQUIRY_METRIC = "paycore.authorization.inquiry"
        const val CALL_DURATION_METRIC = "paycore.authorization.call.duration"

        /** 결과를 만들기 전에 Adapter 밖으로 예외가 나간 호출(닫힌 label 값 — 최종 검토 n-5). */
        const val SENDER_FAILED = "SENDER_FAILED"
    }

    /** 메시지 ID(송신자 생성 ULID — 시각 48bit + 난수 80bit, Crockford base32). */
    private class UlidMessageIds(private val clock: PaymentClock) {
        private val random = SecureRandom()

        fun next(): String {
            val out = CharArray(LENGTH)
            var t = clock.now().toEpochMilli()
            for (i in TIME_CHARS - 1 downTo 0) {
                out[i] = ALPHABET[(t and MASK).toInt()]
                t = t ushr BITS
            }
            for (i in TIME_CHARS until LENGTH) out[i] = ALPHABET[random.nextInt(ALPHABET.length)]
            return String(out)
        }

        private companion object {
            const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
            const val LENGTH = 26
            const val TIME_CHARS = 10
            const val BITS = 5
            const val MASK = 0x1FL
        }
    }
}
