package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.application.required.CancelInquiryCall
import com.paycore.payment.payment.application.required.CancelInquiryCallResult
import com.paycore.payment.payment.application.required.CancelInquiryPort
import com.paycore.payment.payment.application.required.CancellationCall
import com.paycore.payment.payment.application.required.CancellationCallResult
import com.paycore.payment.payment.application.required.CancellationPort
import com.paycore.payment.payment.application.required.InquiryUnavailableReason
import com.paycore.payment.payment.application.required.InstitutionRouteProfile
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancelConfirmedTerms
import com.paycore.payment.payment.domain.CancelObservation
import com.paycore.payment.payment.domain.InstitutionOutcome
import com.paycore.payment.payment.domain.ObservationSource
import com.paycore.payment.payment.domain.UnknownCause
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * 가상 VAN 취소 TCP Adapter(CANCEL-001 §6.6 — sim-van-2). 연결·프레임·deadline·watchdog은 승인 Adapter와 같은 [SimVanTransport]를 쓴다(요청당 연결 1, connect / read /
 * 전체 deadline). 메시지는 [SimVanMessages]의 취소 4종(Simulator 코드와 공유하지 않는다 — golden v2로만).
 * - 연결하지 않는 Route(fail-closed → NotSentConfirmed / 조회 UNKNOWN_ROUTE): 모르는 routeRef, 프로토콜이 sim-van-2가 아님(취소 메시지가 없다), Route 환경·기관이 취소 요청
 *   namespace와 다름(rev 0.3 m-4 — 원 승인과 같은 기관·환경·계정인 것은 [CancellationCall]이 보장한다).
 * - 요청을 쓰기 시작한 뒤의 모든 실패는 OutcomeUnknown(TIMEOUT·CONNECTION_LOST·FRAMING·CORRELATION·INTEGRITY). REQUEST_CONFLICT·REJECTED_CLOSED·원 승인 PTX echo 불일치는
 *   CORRELATION(원 결정은 조회로 확인). 취소 금액·조건이 요청과 달라도 그대로 관찰로 넘긴다 — 상충 판정은 domain(CC §4.6)이 한다.
 * - sourceResultId: 결정 `decisionId`, PENDING `pendingId`, NOT_FOUND `inquiryId`, 무효과 종결 `closureId`. 증거는 payload SHA-256만. domain 전이를 부르지 않는다.
 * - 조회의 무효과 종결 요청은 Route 취소 Capability `confirmsNoEffect`가 있을 때만 보내고, 그 밖 Route의 NO_EFFECT_CONFIRMED는 INTEGRITY다.
 * - 지표(Observability): counter `paycore.cancellation.dispatch{outcome=APPROVED·DECLINED·PENDING·UNKNOWN·NOT_SENT·SENDER_FAILED}`,
 *   `paycore.cancellation.inquiry{result=FOUND·PENDING·NOT_FOUND·NO_EFFECT_CONFIRMED·UNAVAILABLE·SENDER_FAILED}`, timer `paycore.cancellation.call.duration{operation=CANCEL·CANCEL_INQUIRY}`.
 *   label에 요청·Attempt·조회 ID, 기관 계정, Route 이름을 넣지 않는다.
 */
@Component
class SimulatorVanCancellationAdapter internal constructor(
    private val routes: ConfiguredInstitutionRouteRegistry,
    private val clock: PaymentClock,
    connector: VanSocketConnector,
    private val registry: MeterRegistry,
    private val messageIds: () -> String,
) : CancellationPort, CancelInquiryPort {

    @Autowired
    constructor(routes: ConfiguredInstitutionRouteRegistry, clock: PaymentClock, registry: MeterRegistry) :
        this(routes, clock, VanSocketConnector.PLAIN, registry, SimVanMessageIds(clock)::next)

    private val transport = SimVanTransport(clock, connector)

    override fun cancel(call: CancellationCall): CancellationCallResult {
        val sample = Timer.start(registry)
        var outcome = SENDER_FAILED
        try {
            val result = cancelOnce(call)
            outcome = when (result) {
                is CancellationCallResult.Approved -> "APPROVED"
                is CancellationCallResult.Declined -> "DECLINED"
                is CancellationCallResult.Pending -> "PENDING"
                is CancellationCallResult.OutcomeUnknown -> "UNKNOWN"
                CancellationCallResult.NotSentConfirmed -> "NOT_SENT"
            }
            return result
        } finally {
            sample.stop(registry.timer(CALL_DURATION_METRIC, "operation", "CANCEL"))
            registry.counter(DISPATCH_METRIC, "outcome", outcome).increment()
        }
    }

    override fun inquire(call: CancelInquiryCall): CancelInquiryCallResult {
        val sample = Timer.start(registry)
        var outcome = SENDER_FAILED
        try {
            val result = inquireOnce(call)
            outcome = when (result) {
                is CancelInquiryCallResult.Found -> if (result.observation.outcome == InstitutionOutcome.PENDING) "PENDING" else "FOUND"
                is CancelInquiryCallResult.NotFound -> "NOT_FOUND"
                is CancelInquiryCallResult.NoEffectConfirmed -> "NO_EFFECT_CONFIRMED"
                is CancelInquiryCallResult.Unavailable -> "UNAVAILABLE"
            }
            return result
        } finally {
            sample.stop(registry.timer(CALL_DURATION_METRIC, "operation", "CANCEL_INQUIRY"))
            registry.counter(INQUIRY_METRIC, "result", outcome).increment()
        }
    }

    /** 취소를 보낼 수 있는 Route(sim-van-2·같은 환경·같은 기관) — 아니면 null(연결하지 않는다). */
    private fun cancelRoute(routeRef: String, request: com.paycore.payment.payment.domain.ProviderRequestRef): ConfiguredInstitutionRouteRegistry.Route? {
        val profile = routes.resolve(routeRef) ?: return null
        if (profile.protocolVersion != InstitutionRouteProfile.CANCEL_PROTOCOL || profile.capabilities.cancel == null) return null
        if (profile.environment != request.environment || profile.institution != request.institution) return null
        return routes.connection(routeRef)
    }

    private fun cancelOnce(call: CancellationCall): CancellationCallResult {
        val route = cancelRoute(call.routeRef, call.providerRequest) ?: return CancellationCallResult.NotSentConfirmed
        val messageId = messageIds()
        val payload = SimVanMessages.cancelRequest(call, messageId, clock.now())
        val socket = transport.connect(route, call.connectDeadline) ?: return CancellationCallResult.NotSentConfirmed
        return socket.use { s ->
            try {
                val (parsed, raw) = transport.exchange(s, route, payload, SimVanMessages::parseCancelReply)
                if (parsed.inReplyTo != messageId || parsed.requestId != call.providerRequest.requestId) throw SimVanProtocolException(UnknownCause.CORRELATION)
                val hash = SimVanTransport.evidenceHash(raw)
                when (parsed) {
                    is SimVanMessages.CancelReply.Approved -> {
                        if (parsed.terms.originalProviderTransactionId != call.original.providerTransactionId) throw SimVanProtocolException(UnknownCause.CORRELATION)
                        CancellationCallResult.Approved(
                            observation(call.attemptId, ObservationSource.RESPONSE, parsed.decisionId, InstitutionOutcome.APPROVED, parsed.terms, hash),
                        )
                    }
                    is SimVanMessages.CancelReply.Declined -> CancellationCallResult.Declined(
                        observation(call.attemptId, ObservationSource.RESPONSE, parsed.decisionId, InstitutionOutcome.DECLINED, null, hash), parsed.declineCategory,
                    )
                    is SimVanMessages.CancelReply.Pending ->
                        CancellationCallResult.Pending(observation(call.attemptId, ObservationSource.RESPONSE, parsed.pendingId, InstitutionOutcome.PENDING, null, hash))
                    is SimVanMessages.CancelReply.Refused -> throw SimVanProtocolException(UnknownCause.CORRELATION)
                }
            } catch (e: SimVanProtocolException) {
                CancellationCallResult.OutcomeUnknown(e.unknownCause)
            } catch (e: RuntimeException) {
                // 쓰기를 시작한 뒤의 예상 밖 실패도 금융효과 없음으로 단정하지 않는다.
                CancellationCallResult.OutcomeUnknown(UnknownCause.CONNECTION_LOST)
            }
        }
    }

    private fun inquireOnce(call: CancelInquiryCall): CancelInquiryCallResult {
        val route = cancelRoute(call.routeRef, call.providerRequest) ?: return CancelInquiryCallResult.Unavailable(InquiryUnavailableReason.UNKNOWN_ROUTE)
        val confirmsNoEffect = checkNotNull(route.capabilities.cancel).confirmsNoEffect
        val messageId = messageIds()
        val payload = SimVanMessages.cancelInquiryRequest(call, messageId, call.closeIfAbsent && confirmsNoEffect)
        val socket = transport.connect(route, clock.now().plus(route.totalDeadline))
            ?: return CancelInquiryCallResult.Unavailable(InquiryUnavailableReason.CONNECTION)
        return socket.use { s ->
            try {
                val (parsed, raw) = transport.exchange(s, route, payload, SimVanMessages::parseCancelInquiryReply)
                if (parsed.inReplyTo != messageId || parsed.inquiryId != call.inquiryId || parsed.originalRequestId != call.providerRequest.requestId) {
                    throw SimVanProtocolException(UnknownCause.CORRELATION)
                }
                val hash = SimVanTransport.evidenceHash(raw)
                when (parsed) {
                    is SimVanMessages.CancelInquiryReply.FoundApproved -> CancelInquiryCallResult.Found(
                        observation(call.attemptId, ObservationSource.INQUIRY, parsed.decisionId, InstitutionOutcome.APPROVED, parsed.terms, hash),
                    )
                    is SimVanMessages.CancelInquiryReply.FoundDeclined -> CancelInquiryCallResult.Found(
                        observation(call.attemptId, ObservationSource.INQUIRY, parsed.decisionId, InstitutionOutcome.DECLINED, null, hash), parsed.declineCategory,
                    )
                    is SimVanMessages.CancelInquiryReply.Pending ->
                        CancelInquiryCallResult.Found(observation(call.attemptId, ObservationSource.INQUIRY, parsed.pendingId, InstitutionOutcome.PENDING, null, hash))
                    is SimVanMessages.CancelInquiryReply.NotFound ->
                        CancelInquiryCallResult.NotFound(observation(call.attemptId, ObservationSource.INQUIRY, parsed.inquiryId, InstitutionOutcome.NOT_FOUND, null, hash))
                    is SimVanMessages.CancelInquiryReply.NoEffect -> if (confirmsNoEffect) {
                        CancelInquiryCallResult.NoEffectConfirmed(
                            observation(call.attemptId, ObservationSource.INQUIRY, parsed.closureId, InstitutionOutcome.NO_EFFECT_CONFIRMED, null, hash),
                        )
                    } else {
                        CancelInquiryCallResult.Unavailable(InquiryUnavailableReason.INTEGRITY)
                    }
                    is SimVanMessages.CancelInquiryReply.Unavailable -> CancelInquiryCallResult.Unavailable(InquiryUnavailableReason.INSTITUTION_UNAVAILABLE)
                }
            } catch (e: SimVanProtocolException) {
                CancelInquiryCallResult.Unavailable(SimVanTransport.unavailableReason(e.unknownCause))
            } catch (e: RuntimeException) {
                CancelInquiryCallResult.Unavailable(InquiryUnavailableReason.CONNECTION)
            }
        }
    }

    private fun observation(
        attemptId: CancelAttemptId, source: ObservationSource, sourceResultId: String, outcome: InstitutionOutcome, terms: CancelConfirmedTerms?, hash: String,
    ) = CancelObservation(attemptId, source, sourceResultId, outcome, terms, hash, clock.now())

    private companion object {
        const val DISPATCH_METRIC = "paycore.cancellation.dispatch"
        const val INQUIRY_METRIC = "paycore.cancellation.inquiry"
        const val CALL_DURATION_METRIC = "paycore.cancellation.call.duration"
        const val SENDER_FAILED = "SENDER_FAILED"
    }
}
