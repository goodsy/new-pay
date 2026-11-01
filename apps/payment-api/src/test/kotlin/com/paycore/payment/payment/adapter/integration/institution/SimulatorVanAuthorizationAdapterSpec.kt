package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.adapter.integration.institution.StubVan.Action
import com.paycore.payment.payment.adapter.integration.institution.StubVan.Companion.bytes
import com.paycore.payment.payment.adapter.integration.institution.StubVan.Companion.reply
import com.paycore.payment.payment.application.required.AuthorizationCall
import com.paycore.payment.payment.application.required.AuthorizationCallResult
import com.paycore.payment.payment.application.required.AuthorizationEvidenceRef
import com.paycore.payment.payment.application.required.AuthorizationInquiryCall
import com.paycore.payment.payment.application.required.InquiryCallResult
import com.paycore.payment.payment.application.required.InquiryUnavailableReason
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.CardType
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
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger

/**
 * PG 쪽 가상 VAN TCP Adapter(AUTH-001 §5.7): 요청 프레임은 golden과 바이트가 같고, 응답은 §5.7.2 표대로 Port 결과가 된다(관찰 sourceResultId — §5.7.3: decisionId·pendingId
 * (H-3)·inquiryId·closureId). 전송 시작 뒤 모든 실패는 OutcomeUnknown(종류별 cause), connect 단계 실패만 예산 안 재시도 뒤 NotSentConfirmed. 시험 전용 TCP stub VAN
 * (실제 소켓)을 쓴다 — Simulator 앱에 의존하지 않는다(harness test_simulator_isolation).
 */
class SimulatorVanAuthorizationAdapterSpec : BehaviorSpec({

    val now = Instant.parse("2026-10-16T01:00:31.000Z")
    val clock = object : PaymentClock {
        override fun now(): Instant = now
    }
    val attemptId = AttemptId("demo-att-0001")
    val requestId = "ar_01JA0000000000000000000001"

    fun call(routeRef: String = "SIMVAN01", money: Money = Money(10_000, CurrencyCode("KRW")), plan: PaymentPlan = PaymentPlan.LumpSum, connectDeadline: Instant = now.plusSeconds(30)) =
        AuthorizationCall(
            attemptId = attemptId,
            providerRequest = ProviderRequestRef("SIMVAN", Environment.TEST, "SIMMERCH0001", "AUTHORIZE", requestId),
            routeRef = routeRef,
            merchantRef = "mid-demo-0001",
            money = money,
            plan = plan,
            evidence = AuthorizationEvidenceRef("sim-ev-A", "SIMAUTH1", "SIMISSA", "SIMACQA"),
            connectDeadline = connectDeadline,
        )

    fun inquiryCall(closeIfAbsent: Boolean = false, routeRef: String = "SIMVAN01") = AuthorizationInquiryCall(
        attemptId = attemptId,
        providerRequest = ProviderRequestRef("SIMVAN", Environment.TEST, "SIMMERCH0001", "AUTHORIZE", requestId),
        routeRef = routeRef,
        inquiryId = "aq_01JA0000000000000000000002",
        closeIfAbsent = closeIfAbsent,
    )

    fun adapter(
        port: Int,
        confirmsNoEffect: Boolean = true,
        connector: VanSocketConnector = VanSocketConnector.PLAIN,
        meters: MeterRegistry = SimpleMeterRegistry(),
        vararg extra: Pair<String, ConfiguredInstitutionRouteRegistry.Route>,
    ) =
        SimulatorVanAuthorizationAdapter(
            VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(port, confirmsNoEffect = confirmsNoEffect), *extra),
            clock,
            connector,
            meters,
        ) { "01JA0000000000000000000010" }

    fun withStub(handler: (tools.jackson.databind.JsonNode) -> Action, block: (StubVan) -> Unit) = StubVan(handler).use(block)

    fun sha(payload: ByteArray) = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))

    Given("an authorization call to the stub VAN") {
        Then("the request frame is byte-identical to the golden AUTH_REQUEST frame (contract sim-van-1, test-only key)") {
            withStub({ Action.Close }) { stub ->
                adapter(stub.port).authorize(call())
                stub.receivedFrames.single() shouldBe HexFormat.of().parseHex(StubVan.GOLDEN.get("frames").get("authRequest").stringValue())
            }
        }
        Then("APPROVED → Approved with the approved terms, sourceResultId = decisionId and the payload hash as evidence (§5.7.2·§5.7.3)") {
            var payload = ByteArray(0)
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r)).also { payload = it }) }) { stub ->
                val o = adapter(stub.port).authorize(call()).shouldBeInstanceOf<AuthorizationCallResult.Approved>().observation
                o.attemptId shouldBe attemptId
                o.source shouldBe ObservationSource.RESPONSE
                o.sourceResultId shouldBe "vd_01JA0000000000000000000003"
                o.outcome shouldBe InstitutionOutcome.APPROVED
                o.approved.shouldNotBeNull() shouldBeEqual ApprovedTerms(
                    Money(10_000, CurrencyCode("KRW")), PaymentPlan.LumpSum, CardType.CREDIT, "30000001", "SIMPTX-0000000001", LocalDate.of(2026, 10, 16),
                    institutionBusinessDate = LocalDate.of(2026, 10, 16),
                )
                o.evidenceHash shouldBe sha(payload)
                o.observedAt shouldBe now
            }
        }
        Then("DECLINED → Declined (sourceResultId = decisionId); PENDING → Pending with sourceResultId = pendingId (rev 0.3 H-3)") {
            withStub({ r -> Action.Respond(bytes(reply("authResponseDeclined", r))) }) { stub ->
                val o = adapter(stub.port).authorize(call()).shouldBeInstanceOf<AuthorizationCallResult.Declined>().observation
                o.outcome shouldBe InstitutionOutcome.DECLINED
                o.sourceResultId shouldBe "vd_01JA0000000000000000000003"
            }
            withStub({ r -> Action.Respond(bytes(reply("authResponsePending", r))) }) { stub ->
                val o = adapter(stub.port).authorize(call()).shouldBeInstanceOf<AuthorizationCallResult.Pending>().observation
                o.outcome shouldBe InstitutionOutcome.PENDING
                o.sourceResultId shouldBe "vp_01JA0000000000000000000005"
            }
        }
        Then("an installment call carries the plan months on the wire") {
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r))) }) { stub ->
                adapter(stub.port).authorize(call(plan = PaymentPlan.Installment(3)))
                stub.received.single().get("plan") shouldBe StubVan.golden("authRequestInstallment").get("plan")
            }
        }
        Then("a response written a few bytes at a time and a duplicated response frame are read as one response (partial read contract)") {
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r)), chunk = 3, chunkDelayMillis = 2) }) { stub ->
                adapter(stub.port).authorize(call()).shouldBeInstanceOf<AuthorizationCallResult.Approved>()
            }
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r)), times = 2) }) { stub ->
                adapter(stub.port).authorize(call()).shouldBeInstanceOf<AuthorizationCallResult.Approved>()
            }
        }
    }

    Given("responses the PG cannot accept after the request was written (OutcomeUnknown — AC1 §5.2)") {
        Then("REQUEST_CONFLICT and REJECTED_CLOSED are CORRELATION (contract breach, inquire the original decision)") {
            listOf("authResponseRequestConflict", "authResponseRejectedClosed").forEach { name ->
                withStub({ r -> Action.Respond(bytes(reply(name, r))) }) { stub ->
                    withClue(name) { adapter(stub.port).authorize(call()) shouldBe AuthorizationCallResult.OutcomeUnknown(UnknownCause.CORRELATION) }
                }
            }
        }
        Then("an echo mismatch (inReplyTo or requestId) is CORRELATION") {
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r).put("inReplyTo", "01JA0000000000000000000099"))) }) { stub ->
                adapter(stub.port).authorize(call()) shouldBe AuthorizationCallResult.OutcomeUnknown(UnknownCause.CORRELATION)
            }
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r).put("requestId", "ar_01JA0000000000000000000099"))) }) { stub ->
                adapter(stub.port).authorize(call()) shouldBe AuthorizationCallResult.OutcomeUnknown(UnknownCause.CORRELATION)
            }
        }
        Then("a wrong MAC or an unknown key id is INTEGRITY") {
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r)), brokenMac = true) }) { stub ->
                adapter(stub.port).authorize(call()) shouldBe AuthorizationCallResult.OutcomeUnknown(UnknownCause.INTEGRITY)
            }
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r)), keyId = "sim-van-test-9") }) { stub ->
                adapter(stub.port).authorize(call()) shouldBe AuthorizationCallResult.OutcomeUnknown(UnknownCause.INTEGRITY)
            }
        }
        Then("golden malformed responses (unknown field, null, string amount, missing field, approval without terms) are FRAMING") {
            StubVan.GOLDEN.get("malformedAuthResponses").properties().forEach { (name, body) ->
                withStub({ r ->
                    val m = (body.deepCopy() as tools.jackson.databind.node.ObjectNode).put("inReplyTo", r.get("messageId").stringValue())
                    Action.Respond(bytes(m))
                }) { stub ->
                    withClue(name) { adapter(stub.port).authorize(call()) shouldBe AuthorizationCallResult.OutcomeUnknown(UnknownCause.FRAMING) }
                }
            }
        }
        Then("the connection closing without a response is CONNECTION_LOST; silence beyond the read timeout is TIMEOUT") {
            withStub({ Action.Close }) { stub -> adapter(stub.port).authorize(call()) shouldBe AuthorizationCallResult.OutcomeUnknown(UnknownCause.CONNECTION_LOST) }
            withStub({ Action.Silent(2_000) }) { stub ->
                val started = System.nanoTime()
                adapter(stub.port).authorize(call()) shouldBe AuthorizationCallResult.OutcomeUnknown(UnknownCause.TIMEOUT)
                ((System.nanoTime() - started) / 1_000_000) shouldBeLessThan 1_800L
            }
        }
    }

    // 검토 #2 m-A: 전체 deadline은 read 한 번이 아니라 응답 전체에 적용된다 — 조금씩 흘려 보내는 상대가 read timeout 안쪽 간격으로 보내도 deadline에서 끝난다.
    Given("a peer that drips the response one byte at a time within the read timeout (review #2 m-A)") {
        Then("the call ends at the total deadline with OutcomeUnknown(TIMEOUT)") {
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r)), chunk = 1, chunkDelayMillis = 100) }) { stub ->
                val started = System.nanoTime()
                adapter(stub.port).authorize(call()) shouldBe AuthorizationCallResult.OutcomeUnknown(UnknownCause.TIMEOUT)
                ((System.nanoTime() - started) / 1_000_000) shouldBeLessThan 3_000L // 전체 deadline 2초(Demo) + 여유
            }
            withStub({ r -> Action.Respond(bytes(reply("inquiryResponseFound", r)), chunk = 1, chunkDelayMillis = 100) }) { stub ->
                adapter(stub.port).inquire(inquiryCall()) shouldBe InquiryCallResult.Unavailable(InquiryUnavailableReason.TIMEOUT)
            }
        }
    }

    // 검토 #2 m-B: Route 환경과 요청 환경이 다르면 연결하지 않는다(TEST Route로 LIVE 요청 바이트를 보내지 않는다).
    Given("a LIVE request on a TEST route (review #2 m-B)") {
        Then("authorization is NotSentConfirmed and inquiry Unavailable(UNKNOWN_ROUTE) with no connection") {
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r))) }) { stub ->
                val live = call().copy(providerRequest = ProviderRequestRef("SIMVAN", Environment.LIVE, "SIMMERCH0001", "AUTHORIZE", requestId))
                adapter(stub.port).authorize(live) shouldBe AuthorizationCallResult.NotSentConfirmed
                val liveInquiry = inquiryCall().copy(providerRequest = ProviderRequestRef("SIMVAN", Environment.LIVE, "SIMMERCH0001", "AUTHORIZE", requestId))
                adapter(stub.port).inquire(liveInquiry) shouldBe InquiryCallResult.Unavailable(InquiryUnavailableReason.UNKNOWN_ROUTE)
                stub.connections.get() shouldBe 0
            }
        }
    }

    Given("connect-stage failures (nothing written — §5.7.1)") {
        Then("a refused connection is retried within the budget and then NotSentConfirmed") {
            val closed = ServerSocket(0).let { val p = it.localPort; it.close(); p }
            val attempts = AtomicInteger()
            val counting = VanSocketConnector { address, timeout -> attempts.incrementAndGet(); VanSocketConnector.PLAIN.connect(address, timeout) }
            adapter(closed, connector = counting).authorize(call()) shouldBe AuthorizationCallResult.NotSentConfirmed
            attempts.get() shouldBe 3 // 첫 시도 + 재시도 예산 2(Demo)
        }
        Then("no connect is attempted once the dispatch deadline has passed (AU-07·AU-34)") {
            val attempts = AtomicInteger()
            val counting = VanSocketConnector { address, timeout -> attempts.incrementAndGet(); VanSocketConnector.PLAIN.connect(address, timeout) }
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r))) }) { stub ->
                adapter(stub.port, connector = counting).authorize(call(connectDeadline = now)) shouldBe AuthorizationCallResult.NotSentConfirmed
                attempts.get() shouldBe 0
                stub.connections.get() shouldBe 0
            }
        }
        Then("an unknown routeRef is fail-closed: NotSentConfirmed for authorization, Unavailable for inquiry, no connection") {
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r))) }) { stub ->
                adapter(stub.port).authorize(call(routeRef = "SIMVAN99")) shouldBe AuthorizationCallResult.NotSentConfirmed
                adapter(stub.port).inquire(inquiryCall(routeRef = "SIMVAN99")) shouldBe InquiryCallResult.Unavailable(InquiryUnavailableReason.UNKNOWN_ROUTE)
                stub.connections.get() shouldBe 0
            }
        }
    }

    Given("inquiries by the original request ID (§5.4, §5.7.2)") {
        Then("FOUND → Found with the original decision (source INQUIRY, sourceResultId = decisionId); the request echoes the original request ID") {
            withStub({ r -> Action.Respond(bytes(reply("inquiryResponseFound", r))) }) { stub ->
                val o = adapter(stub.port).inquire(inquiryCall()).shouldBeInstanceOf<InquiryCallResult.Found>().observation
                o.source shouldBe ObservationSource.INQUIRY
                o.sourceResultId shouldBe "vd_01JA0000000000000000000003"
                o.outcome shouldBe InstitutionOutcome.APPROVED
                stub.received.single().get("originalRequestId").stringValue() shouldBe requestId
                stub.received.single().get("type").stringValue() shouldBe "INQUIRY_REQUEST"
            }
        }
        Then("PENDING → Found with a PENDING observation whose sourceResultId is the pendingId — not a termination ground (rev 0.3 H-3)") {
            withStub({ r -> Action.Respond(bytes(reply("inquiryResponsePending", r))) }) { stub ->
                val o = adapter(stub.port).inquire(inquiryCall()).shouldBeInstanceOf<InquiryCallResult.Found>().observation
                o.outcome shouldBe InstitutionOutcome.PENDING
                o.sourceResultId shouldBe "vp_01JA0000000000000000000005"
            }
        }
        Then("NOT_FOUND → NotFound with a NOT_FOUND observation keyed by the inquiryId (one inquiry = one observation)") {
            withStub({ r -> Action.Respond(bytes(reply("inquiryResponseNotFound", r))) }) { stub ->
                val o = adapter(stub.port).inquire(inquiryCall()).shouldBeInstanceOf<InquiryCallResult.NotFound>().observation
                o.outcome shouldBe InstitutionOutcome.NOT_FOUND
                o.sourceResultId shouldBe "aq_01JA0000000000000000000002"
            }
        }
        Then("NO_EFFECT_CONFIRMED → NoEffectConfirmed (sourceResultId = closureId) only for a route with confirmsNoEffect; from profile B it is INTEGRITY") {
            withStub({ r -> Action.Respond(bytes(reply("inquiryResponseNoEffectConfirmed", r))) }) { stub ->
                val o = adapter(stub.port).inquire(inquiryCall(closeIfAbsent = true)).shouldBeInstanceOf<InquiryCallResult.NoEffectConfirmed>().observation
                o.outcome shouldBe InstitutionOutcome.NO_EFFECT_CONFIRMED
                o.sourceResultId shouldBe "vc_01JA0000000000000000000004"
                stub.received.single().get("closeIfAbsent").booleanValue() shouldBe true
            }
            withStub({ r -> Action.Respond(bytes(reply("inquiryResponseNoEffectConfirmed", r))) }) { stub ->
                adapter(stub.port, confirmsNoEffect = false).inquire(inquiryCall(closeIfAbsent = true)) shouldBe InquiryCallResult.Unavailable(InquiryUnavailableReason.INTEGRITY)
                stub.received.single().get("closeIfAbsent").booleanValue() shouldBe false // 프로파일 B에는 종결 요청을 보내지 않는다
            }
        }
        Then("UNAVAILABLE, a refused connection and a timeout are Unavailable") {
            withStub({ r -> Action.Respond(bytes(reply("inquiryResponseUnavailable", r))) }) { stub ->
                adapter(stub.port).inquire(inquiryCall()) shouldBe InquiryCallResult.Unavailable(InquiryUnavailableReason.INSTITUTION_UNAVAILABLE)
            }
            val closed = ServerSocket(0).let { val p = it.localPort; it.close(); p }
            adapter(closed).inquire(inquiryCall()) shouldBe InquiryCallResult.Unavailable(InquiryUnavailableReason.CONNECTION)
            withStub({ Action.Silent(2_000) }) { stub -> adapter(stub.port).inquire(inquiryCall()) shouldBe InquiryCallResult.Unavailable(InquiryUnavailableReason.TIMEOUT) }
        }
    }

    // AUTH-001 §9 Observability(S8 gap 4): 기관 호출마다 결과 counter와 호출 시간 timer. label은 닫힌 값 집합만(ID·기관 계정·요청 ID 없음).
    Given("dispatch and inquiry metrics (§9 Observability)") {
        fun dispatches(meters: MeterRegistry, outcome: String) = meters.find("paycore.authorization.dispatch").tag("outcome", outcome).counter()?.count() ?: 0.0
        fun inquiries(meters: MeterRegistry, result: String) = meters.find("paycore.authorization.inquiry").tag("result", result).counter()?.count() ?: 0.0
        fun calls(meters: MeterRegistry, operation: String) = meters.find("paycore.authorization.call.duration").tag("operation", operation).timer()?.count() ?: 0L

        Then("every authorization call counts paycore.authorization.dispatch{outcome} once and records paycore.authorization.call.duration{operation=AUTHORIZE}") {
            val meters = SimpleMeterRegistry()
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r))) }) { stub -> adapter(stub.port, meters = meters).authorize(call()) }
            withStub({ r -> Action.Respond(bytes(reply("authResponseDeclined", r))) }) { stub -> adapter(stub.port, meters = meters).authorize(call()) }
            withStub({ r -> Action.Respond(bytes(reply("authResponsePending", r))) }) { stub -> adapter(stub.port, meters = meters).authorize(call()) }
            withStub({ Action.Close }) { stub -> adapter(stub.port, meters = meters).authorize(call()) }
            val closed = ServerSocket(0).let { val p = it.localPort; it.close(); p }
            adapter(closed, meters = meters).authorize(call())
            adapter(closed, meters = meters).authorize(call(routeRef = "SIMVAN99"))
            mapOf("APPROVED" to 1.0, "DECLINED" to 1.0, "PENDING" to 1.0, "UNKNOWN" to 1.0, "NOT_SENT" to 2.0).forEach { (outcome, n) ->
                withClue(outcome) { dispatches(meters, outcome) shouldBe n }
            }
            calls(meters, "AUTHORIZE") shouldBe 6L
            calls(meters, "INQUIRY") shouldBe 0L
        }
        Then("every inquiry counts paycore.authorization.inquiry{result} once and records the call duration with operation=INQUIRY") {
            val meters = SimpleMeterRegistry()
            listOf("inquiryResponseFound", "inquiryResponsePending", "inquiryResponseNotFound", "inquiryResponseNoEffectConfirmed", "inquiryResponseUnavailable").forEach { name ->
                withStub({ r -> Action.Respond(bytes(reply(name, r))) }) { stub -> adapter(stub.port, meters = meters).inquire(inquiryCall(closeIfAbsent = true)) }
            }
            adapter(1, meters = meters).inquire(inquiryCall(routeRef = "SIMVAN99"))
            mapOf("FOUND" to 1.0, "PENDING" to 1.0, "NOT_FOUND" to 1.0, "NO_EFFECT_CONFIRMED" to 1.0, "UNAVAILABLE" to 2.0).forEach { (result, n) ->
                withClue(result) { inquiries(meters, result) shouldBe n }
            }
            calls(meters, "INQUIRY") shouldBe 6L
        }
        // 최종 검토 n-5: socket.use 밖(연결 단계 등)의 예상 밖 예외도 timer를 멈추고 닫힌 값 SENDER_FAILED로 센다 — 예외는 그대로 호출자에게 간다(Dispatch는 202·회복).
        Then("an unexpected exception outside the socket exchange still stops the timer and counts outcome/result=SENDER_FAILED, then propagates") {
            val meters = SimpleMeterRegistry()
            val broken = VanSocketConnector { _, _ -> throw IllegalStateException("injected connector defect") }
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r))) }) { stub ->
                shouldThrow<IllegalStateException> { adapter(stub.port, connector = broken, meters = meters).authorize(call()) }
                shouldThrow<IllegalStateException> { adapter(stub.port, connector = broken, meters = meters).inquire(inquiryCall()) }
                stub.connections.get() shouldBe 0
            }
            dispatches(meters, "SENDER_FAILED") shouldBe 1.0
            inquiries(meters, "SENDER_FAILED") shouldBe 1.0
            calls(meters, "AUTHORIZE") shouldBe 1L
            calls(meters, "INQUIRY") shouldBe 1L
            val tags = meters.meters.filter { it.id.name.startsWith("paycore.authorization") }.flatMap { it.id.tags }
            tags.map { it.key }.toSet() shouldBe setOf("outcome", "result", "operation")
        }
        Then("label names are only outcome, result or operation, and no label value carries a request, attempt, inquiry or institution account ID") {
            val meters = SimpleMeterRegistry()
            withStub({ r -> Action.Respond(bytes(reply("authResponseApproved", r))) }) { stub ->
                adapter(stub.port, meters = meters).authorize(call())
            }
            withStub({ r -> Action.Respond(bytes(reply("inquiryResponseNotFound", r))) }) { stub -> adapter(stub.port, meters = meters).inquire(inquiryCall()) }
            val tags = meters.meters.filter { it.id.name.startsWith("paycore.authorization") }.flatMap { it.id.tags }
            tags.map { it.key }.toSet() shouldBe setOf("outcome", "result", "operation")
            tags.map { it.value }.forEach { v ->
                withClue(v) { listOf(requestId, attemptId.value, "aq_", "SIMMERCH0001", "mid-demo-0001").any { v.contains(it) } shouldBe false }
            }
        }
    }

    Given("the plain socket connector") {
        Then("it connects with the given timeout") {
            ServerSocket(0).use { s ->
                VanSocketConnector.PLAIN.connect(InetSocketAddress("127.0.0.1", s.localPort), 500).use { c: Socket -> c.isConnected shouldBe true }
            }
        }
    }
})
