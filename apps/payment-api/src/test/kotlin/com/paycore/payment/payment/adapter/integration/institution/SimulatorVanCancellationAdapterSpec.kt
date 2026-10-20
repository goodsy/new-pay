package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.adapter.integration.institution.StubVan.Action
import com.paycore.payment.payment.adapter.integration.institution.StubVan.Companion.bytes
import com.paycore.payment.payment.adapter.integration.institution.StubVan.Companion.replyV2
import com.paycore.payment.payment.application.required.AuthorizationCall
import com.paycore.payment.payment.application.required.AuthorizationEvidenceRef
import com.paycore.payment.payment.application.required.CancelInquiryCall
import com.paycore.payment.payment.application.required.CancelInquiryCallResult
import com.paycore.payment.payment.application.required.CancellationCall
import com.paycore.payment.payment.application.required.CancellationCallResult
import com.paycore.payment.payment.application.required.InquiryUnavailableReason
import com.paycore.payment.payment.application.required.OriginalAuthorizationRef
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancelConfirmedTerms
import com.paycore.payment.payment.domain.CancelScope
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.InstitutionOutcome
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.ObservationSource
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.ProviderRequestRef
import com.paycore.payment.payment.domain.UnknownCause
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import tools.jackson.databind.JsonNode
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat

/**
 * PG 쪽 가상 VAN 취소 Adapter(CANCEL-001 §6.6 — sim-van-2): 요청 프레임은 golden v2와 바이트가 같고, 응답은 §6.6 표대로 Port 결과가 된다(관찰 sourceResultId: 결정
 * `decisionId`, PENDING `pendingId`, NOT_FOUND `inquiryId`, 무효과 종결 `closureId`). 전송 시작 뒤 모든 실패는 OutcomeUnknown(종류별 cause), connect 단계 실패만 예산 안
 * 재시도 뒤 NotSentConfirmed. sim-van-2가 아닌 Route·모르는 Route·환경/기관이 원 승인과 다른 Route(m-4)에는 연결하지 않는다. 취소 승인번호가 없으면 null(n-5 — 필드 생략),
 * null 값은 FRAMING. 시험 전용 TCP stub VAN(실제 소켓) — Simulator 앱에 의존하지 않는다.
 */
class SimulatorVanCancellationAdapterSpec : BehaviorSpec({

    val now = Instant.parse("2026-10-17T02:00:00.000Z")
    val clock = object : PaymentClock {
        override fun now(): Instant = now
    }
    val attemptId = CancelAttemptId("demo-pca-0001")
    val cancelRequestId = "cr_01JA0000000000000000000021"
    val original = OriginalAuthorizationRef(
        providerRequest = ProviderRequestRef("SIMVAN", Environment.TEST, "SIMMERCH0001", "AUTHORIZE", "ar_01JA0000000000000000000001"),
        providerTransactionId = "SIMPTX-0000000001",
        approvalNumber = "30000001",
        transactionDate = LocalDate.parse("2026-10-16"),
    )
    val krw = CurrencyCode("KRW")

    fun call(
        routeRef: String = "SIM-VAN-01",
        money: Money = Money(3_000, krw),
        scope: CancelScope = CancelScope.PARTIAL,
        connectDeadline: Instant = now.plusSeconds(30),
        orig: OriginalAuthorizationRef = original,
    ) = CancellationCall(
        attemptId = attemptId,
        providerRequest = ProviderRequestRef("SIMVAN", Environment.TEST, "SIMMERCH0001", "CANCEL", cancelRequestId),
        routeRef = routeRef,
        merchantRef = "mid-demo-0001",
        original = orig,
        money = money,
        scope = scope,
        connectDeadline = connectDeadline,
    )

    fun inquiryCall(closeIfAbsent: Boolean = false, routeRef: String = "SIM-VAN-01") = CancelInquiryCall(
        attemptId = attemptId,
        providerRequest = ProviderRequestRef("SIMVAN", Environment.TEST, "SIMMERCH0001", "CANCEL", cancelRequestId),
        routeRef = routeRef,
        inquiryId = "cq_01JA0000000000000000000022",
        closeIfAbsent = closeIfAbsent,
    )

    fun adapter(
        route: (Int) -> ConfiguredInstitutionRouteRegistry.Route = { VanRouteFixture.cancelRoute(it) },
        port: Int,
        connector: VanSocketConnector = VanSocketConnector.PLAIN,
        meters: MeterRegistry = SimpleMeterRegistry(),
        messageId: String = "01JA0000000000000000000030",
    ) = SimulatorVanCancellationAdapter(VanRouteFixture.registry("SIM-VAN-01" to route(port)), clock, connector, meters) { messageId }

    fun withStub(handler: (JsonNode) -> Action, block: (StubVan) -> Unit) = StubVan(handler).use(block)

    fun sha(payload: ByteArray) = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))

    val approvedTerms = CancelConfirmedTerms(
        money = Money(3_000, krw), originalProviderTransactionId = "SIMPTX-0000000001", providerTransactionId = "SIMPTX-C000000001", cancelApprovalNumber = "40000001",
        institutionTransactionDate = LocalDate.parse("2026-10-17"), institutionBusinessDate = LocalDate.parse("2026-10-17"),
    )

    Given("a cancel call to the stub VAN over a sim-van-2 route") {
        Then("the request frame is byte-identical to the golden v2 CANCEL_REQUEST frame") {
            withStub({ Action.Close }) { stub ->
                adapter(port = stub.port).cancel(call())
                stub.receivedFrames.single() shouldBe HexFormat.of().parseHex(StubVan.GOLDEN_V2.get("frames").get("cancelRequest").stringValue())
            }
        }
        Then("APPROVED → Approved with the cancel terms, sourceResultId = decisionId and the payload hash as evidence") {
            var payload = ByteArray(0)
            withStub({ r -> Action.Respond(bytes(replyV2("cancelResponseApproved", r)).also { payload = it }) }) { stub ->
                val o = adapter(port = stub.port).cancel(call()).shouldBeInstanceOf<CancellationCallResult.Approved>().observation
                o.attemptId shouldBe attemptId
                o.source shouldBe ObservationSource.RESPONSE
                o.sourceResultId shouldBe "vd_01JA0000000000000000000023"
                o.outcome shouldBe InstitutionOutcome.APPROVED
                o.confirmed shouldBe approvedTerms
                o.evidenceHash shouldBe sha(payload)
                o.observedAt shouldBe now
            }
        }
        Then("APPROVED without a cancel approval number → null number (n-5); a different cancelled amount is passed on for the domain to judge") {
            withStub({ r -> Action.Respond(bytes(replyV2("cancelResponseApprovedWithoutApprovalNumber", r))) }) { stub ->
                adapter(port = stub.port).cancel(call()).shouldBeInstanceOf<CancellationCallResult.Approved>().observation.confirmed?.cancelApprovalNumber shouldBe null
            }
            withStub({ r -> Action.Respond(bytes(replyV2("cancelResponseApproved", r).put("cancelledAmountMinor", 2_999))) }) { stub ->
                adapter(port = stub.port).cancel(call()).shouldBeInstanceOf<CancellationCallResult.Approved>().observation.confirmed?.money shouldBe Money(2_999, krw)
            }
        }
        Then("DECLINED → Declined with its fixed category; PENDING → Pending with sourceResultId = pendingId") {
            withStub({ r -> Action.Respond(bytes(replyV2("cancelResponseDeclined", r))) }) { stub ->
                val d = adapter(port = stub.port).cancel(call()).shouldBeInstanceOf<CancellationCallResult.Declined>()
                d.observation.outcome shouldBe InstitutionOutcome.DECLINED
                d.observation.sourceResultId shouldBe "vd_01JA0000000000000000000023"
                d.declineCategory shouldBe "EXCEEDS_REMAINING"
            }
            withStub({ r -> Action.Respond(bytes(replyV2("cancelResponsePending", r))) }) { stub ->
                val o = adapter(port = stub.port).cancel(call()).shouldBeInstanceOf<CancellationCallResult.Pending>().observation
                o.outcome shouldBe InstitutionOutcome.PENDING
                o.sourceResultId shouldBe "vp_01JA0000000000000000000025"
            }
        }
        Then("REQUEST_CONFLICT and REJECTED_CLOSED are OutcomeUnknown(CORRELATION) — the original decision is confirmed by inquiry") {
            listOf("cancelResponseRequestConflict", "cancelResponseRejectedClosed").forEach { name ->
                withStub({ r -> Action.Respond(bytes(replyV2(name, r))) }) { stub ->
                    adapter(port = stub.port).cancel(call()) shouldBe CancellationCallResult.OutcomeUnknown(UnknownCause.CORRELATION)
                }
            }
        }
        Then("every golden malformed cancel response (unknown field, null approval number, amount as string, approved without terms) is OutcomeUnknown(FRAMING)") {
            StubVan.GOLDEN_V2.get("malformedCancelResponses").properties().forEach { (name, body) ->
                withStub({ r -> Action.Respond(bytes((body.deepCopy() as tools.jackson.databind.node.ObjectNode).put("inReplyTo", r.get("messageId").stringValue()))) }) { stub ->
                    withClue(name) { adapter(port = stub.port).cancel(call()) shouldBe CancellationCallResult.OutcomeUnknown(UnknownCause.FRAMING) }
                }
            }
            withStub({ r -> Action.Respond(bytes(replyV2("cancelResponseDeclined", r).put("declineCategory", "ISSUER_BUSY"))) }) { stub ->
                adapter(port = stub.port).cancel(call()) shouldBe CancellationCallResult.OutcomeUnknown(UnknownCause.FRAMING)
            }
            withStub({ r -> Action.Respond(bytes(replyV2("authResponseApproved", r))) }) { stub ->
                adapter(port = stub.port).cancel(call()) shouldBe CancellationCallResult.OutcomeUnknown(UnknownCause.FRAMING)
            }
        }
        Then("a wrong inReplyTo, request ID or original PTX echo is OutcomeUnknown(CORRELATION)") {
            listOf<(JsonNode) -> JsonNode>(
                { r -> replyV2("cancelResponseApproved", r).put("inReplyTo", "01JA0000000000000000000099") },
                { r -> replyV2("cancelResponseApproved", r).put("requestId", "cr_01JA0000000000000000000099") },
                { r -> replyV2("cancelResponseApproved", r).put("originalProviderTransactionId", "SIMPTX-0000000002") },
            ).forEach { body ->
                withStub({ r -> Action.Respond(bytes(body(r))) }) { stub ->
                    adapter(port = stub.port).cancel(call()) shouldBe CancellationCallResult.OutcomeUnknown(UnknownCause.CORRELATION)
                }
            }
        }
        Then("a broken MAC is INTEGRITY, a close is CONNECTION_LOST, silence past the read timeout is TIMEOUT, a fragmented response is read whole") {
            withStub({ r -> Action.Respond(bytes(replyV2("cancelResponseApproved", r)), brokenMac = true) }) { stub ->
                adapter(port = stub.port).cancel(call()) shouldBe CancellationCallResult.OutcomeUnknown(UnknownCause.INTEGRITY)
            }
            withStub({ Action.Close }) { stub -> adapter(port = stub.port).cancel(call()) shouldBe CancellationCallResult.OutcomeUnknown(UnknownCause.CONNECTION_LOST) }
            withStub({ Action.Silent(3_000) }) { stub -> adapter(port = stub.port).cancel(call()) shouldBe CancellationCallResult.OutcomeUnknown(UnknownCause.TIMEOUT) }
            withStub({ r -> Action.Respond(bytes(replyV2("cancelResponseApproved", r)), chunk = 3, chunkDelayMillis = 1) }) { stub ->
                adapter(port = stub.port).cancel(call()).shouldBeInstanceOf<CancellationCallResult.Approved>()
            }
        }
    }

    Given("routes the cancel must not be sent to (fail-closed, m-4)") {
        Then("a sim-van-1 route, an unknown route or a route of another institution is NotSentConfirmed without a connection (a LIVE route cannot be registered)") {
            mapOf<String, Pair<(Int) -> ConfiguredInstitutionRouteRegistry.Route, CancellationCall>>(
                "sim-van-1 route" to ({ p: Int -> VanRouteFixture.route(p) } to call()),
                "unknown route" to ({ p: Int -> VanRouteFixture.cancelRoute(p) } to call(routeRef = "SIM-VAN-99")),
                "route of another institution" to ({ p: Int -> VanRouteFixture.cancelRoute(p, institution = "OTHERVAN") } to call()),
            ).forEach { (name, case) ->
                withStub({ r -> Action.Respond(bytes(replyV2("cancelResponseApproved", r))) }) { stub ->
                    withClue(name) {
                        adapter(route = case.first, port = stub.port).cancel(case.second) shouldBe CancellationCallResult.NotSentConfirmed
                        stub.connections.get() shouldBe 0
                    }
                }
            }
        }
        Then("a cancel call whose namespace differs from the original authorization's is a programming error (m-4)") {
            shouldThrow<IllegalArgumentException> {
                call(orig = original.copy(providerRequest = original.providerRequest.copy(institutionAccount = "SIMMERCH0002")))
            }
            shouldThrow<IllegalArgumentException> {
                call(orig = original.copy(providerRequest = original.providerRequest.copy(environment = Environment.LIVE)))
            }
        }
        Then("refused connections retry within the budget and end NotSentConfirmed; a past connect deadline does not connect") {
            val refused = ServerSocket(0).also { it.close() }.localPort
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val counting = VanSocketConnector { address: InetSocketAddress, timeout: Int -> attempts.incrementAndGet(); VanSocketConnector.PLAIN.connect(address, timeout) }
            adapter(port = refused, connector = counting).cancel(call()) shouldBe CancellationCallResult.NotSentConfirmed
            attempts.get() shouldBe 3
            attempts.set(0)
            adapter(port = refused, connector = counting).cancel(call(connectDeadline = now.minusSeconds(1))) shouldBe CancellationCallResult.NotSentConfirmed
            attempts.get() shouldBe 0
        }
    }

    Given("a cancel inquiry (original cancel request ID only)") {
        Then("the inquiry payload is the golden v2 CANCEL_INQUIRY_REQUEST; closeIfAbsent is sent only on a route whose cancel capability confirms no effect") {
            withStub({ Action.Close }) { stub ->
                adapter(port = stub.port, messageId = "01JA0000000000000000000031").inquire(inquiryCall())
                stub.received.single() shouldBe StubVan.goldenV2("cancelInquiryRequest")
            }
            withStub({ Action.Close }) { stub ->
                adapter(port = stub.port, messageId = "01JA0000000000000000000031").inquire(inquiryCall(closeIfAbsent = true))
                stub.received.single() shouldBe StubVan.goldenV2("cancelInquiryRequestClose")
            }
            withStub({ Action.Close }) { stub ->
                adapter(route = { VanRouteFixture.cancelRoute(it, VanRouteFixture.cancelCapabilities(confirmsNoEffect = false)) }, port = stub.port)
                    .inquire(inquiryCall(closeIfAbsent = true))
                stub.received.single().get("closeIfAbsent").booleanValue() shouldBe false
            }
        }
        Then("FOUND approved/declined, PENDING, NOT_FOUND, NO_EFFECT_CONFIRMED and UNAVAILABLE map to the port results with their sourceResultIds") {
            withStub({ r -> Action.Respond(bytes(replyV2("cancelInquiryResponseFound", r))) }) { stub ->
                val o = adapter(port = stub.port).inquire(inquiryCall()).shouldBeInstanceOf<CancelInquiryCallResult.Found>().observation
                o.source shouldBe ObservationSource.INQUIRY
                o.sourceResultId shouldBe "vd_01JA0000000000000000000023"
                o.confirmed shouldBe approvedTerms
            }
            withStub({ r -> Action.Respond(bytes(replyV2("cancelInquiryResponseFoundDeclined", r))) }) { stub ->
                val f = adapter(port = stub.port).inquire(inquiryCall()).shouldBeInstanceOf<CancelInquiryCallResult.Found>()
                f.observation.outcome shouldBe InstitutionOutcome.DECLINED
                f.declineCategory shouldBe "CANCEL_PERIOD_EXPIRED"
            }
            withStub({ r -> Action.Respond(bytes(replyV2("cancelInquiryResponsePending", r))) }) { stub ->
                val o = adapter(port = stub.port).inquire(inquiryCall()).shouldBeInstanceOf<CancelInquiryCallResult.Found>().observation
                o.outcome shouldBe InstitutionOutcome.PENDING
                o.sourceResultId shouldBe "vp_01JA0000000000000000000025"
            }
            withStub({ r -> Action.Respond(bytes(replyV2("cancelInquiryResponseNotFound", r))) }) { stub ->
                val o = adapter(port = stub.port).inquire(inquiryCall()).shouldBeInstanceOf<CancelInquiryCallResult.NotFound>().observation
                o.outcome shouldBe InstitutionOutcome.NOT_FOUND
                o.sourceResultId shouldBe "cq_01JA0000000000000000000022"
            }
            withStub({ r -> Action.Respond(bytes(replyV2("cancelInquiryResponseNoEffectConfirmed", r))) }) { stub ->
                val o = adapter(port = stub.port).inquire(inquiryCall(closeIfAbsent = true)).shouldBeInstanceOf<CancelInquiryCallResult.NoEffectConfirmed>().observation
                o.outcome shouldBe InstitutionOutcome.NO_EFFECT_CONFIRMED
                o.sourceResultId shouldBe "vc_01JA0000000000000000000024"
            }
            withStub({ r -> Action.Respond(bytes(replyV2("cancelInquiryResponseUnavailable", r))) }) { stub ->
                adapter(port = stub.port).inquire(inquiryCall()) shouldBe CancelInquiryCallResult.Unavailable(InquiryUnavailableReason.INSTITUTION_UNAVAILABLE)
            }
        }
        Then("NO_EFFECT_CONFIRMED from a route without the cancel no-effect capability is INTEGRITY; a mismatched echo is CORRELATION; a sim-van-1 route is UNKNOWN_ROUTE") {
            withStub({ r -> Action.Respond(bytes(replyV2("cancelInquiryResponseNoEffectConfirmed", r))) }) { stub ->
                adapter(route = { VanRouteFixture.cancelRoute(it, VanRouteFixture.cancelCapabilities(confirmsNoEffect = false)) }, port = stub.port)
                    .inquire(inquiryCall(closeIfAbsent = true)) shouldBe CancelInquiryCallResult.Unavailable(InquiryUnavailableReason.INTEGRITY)
            }
            withStub({ r -> Action.Respond(bytes(replyV2("cancelInquiryResponseFound", r).put("originalRequestId", "cr_01JA0000000000000000000099"))) }) { stub ->
                adapter(port = stub.port).inquire(inquiryCall()) shouldBe CancelInquiryCallResult.Unavailable(InquiryUnavailableReason.CORRELATION)
            }
            withStub({ r -> Action.Respond(bytes(replyV2("cancelInquiryResponseFound", r))) }) { stub ->
                adapter(route = { VanRouteFixture.route(it) }, port = stub.port).inquire(inquiryCall()) shouldBe
                    CancelInquiryCallResult.Unavailable(InquiryUnavailableReason.UNKNOWN_ROUTE)
                stub.connections.get() shouldBe 0
            }
        }
    }

    Given("observability (closed label values, no identifiers)") {
        Then("dispatch and inquiry counters and the call timer are recorded per call") {
            val meters = SimpleMeterRegistry()
            withStub({ r -> Action.Respond(bytes(replyV2("cancelResponseApproved", r))) }) { stub -> adapter(port = stub.port, meters = meters).cancel(call()) }
            withStub({ Action.Close }) { stub -> adapter(port = stub.port, meters = meters).cancel(call()) }
            withStub({ r -> Action.Respond(bytes(replyV2("cancelInquiryResponseNotFound", r))) }) { stub -> adapter(port = stub.port, meters = meters).inquire(inquiryCall()) }
            meters.counter("paycore.cancellation.dispatch", "outcome", "APPROVED").count() shouldBe 1.0
            meters.counter("paycore.cancellation.dispatch", "outcome", "UNKNOWN").count() shouldBe 1.0
            meters.counter("paycore.cancellation.inquiry", "result", "NOT_FOUND").count() shouldBe 1.0
            meters.timer("paycore.cancellation.call.duration", "operation", "CANCEL").count() shouldBe 2L
            meters.timer("paycore.cancellation.call.duration", "operation", "CANCEL_INQUIRY").count() shouldBe 1L
        }
    }

    // m-7: sim-van-2 Route로 보낸 승인 프레임은 sim-van-1 golden 벡터와 같다(프레임·메시지 불변 — 프로토콜 버전은 Route 감사값일 뿐).
    Given("an authorization sent over a sim-van-2 route") {
        Then("the AUTH_REQUEST frame is byte-identical to the sim-van-1 golden frame") {
            withStub({ Action.Close }) { stub ->
                SimulatorVanAuthorizationAdapter(
                    VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.cancelRoute(stub.port)), object : PaymentClock {
                        override fun now(): Instant = Instant.parse("2026-10-16T01:00:31.000Z")
                    },
                    VanSocketConnector.PLAIN, SimpleMeterRegistry(),
                ) { "01JA0000000000000000000010" }.authorize(
                    AuthorizationCall(
                        attemptId = AttemptId("demo-att-0001"),
                        providerRequest = ProviderRequestRef("SIMVAN", Environment.TEST, "SIMMERCH0001", "AUTHORIZE", "ar_01JA0000000000000000000001"),
                        routeRef = "SIM-VAN-01", merchantRef = "mid-demo-0001", money = Money(10_000, krw), plan = PaymentPlan.LumpSum,
                        evidence = AuthorizationEvidenceRef("sim-ev-A", "SIMAUTH-1", "SIMISS-A", "SIMACQ-A"), connectDeadline = Instant.parse("2026-10-16T01:01:00.000Z"),
                    ),
                )
                stub.receivedFrames.single() shouldBe HexFormat.of().parseHex(StubVan.GOLDEN.get("frames").get("authRequest").stringValue())
                StubVan.GOLDEN_V2.get("frames").get("authRequest") shouldBe StubVan.GOLDEN.get("frames").get("authRequest")
            }
        }
    }
})
