package com.paycore.admin.paymentops

import com.paycore.admin.paymentops.adapter.integration.AdminCallSigner
import com.paycore.admin.paymentops.adapter.integration.HttpPaymentOpsGateway
import com.paycore.admin.paymentops.adapter.integration.PaymentOpsSettings
import com.paycore.admin.paymentops.application.provided.DecisionContent
import com.paycore.admin.paymentops.application.provided.DecisionResult
import com.paycore.admin.support.StubPaymentOpsServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * 기록 결정 조회·기한 확인 응답(ADMIN-PAYMENT-OPS-001 §4.3·§4.4.5 — 계약 golden ③ `decisionResponses` 3개·`deadlineResponses` 4개)을 실제 HTTP 대역으로 재생해
 * 기대 ADMIN 상태(EXECUTED·OWNER_REJECTED·UNCHANGED)와 기한 확인의 `confirmed`·차단 해제 여부로 분류되는지 대조한다. 기한 확인은 **저장 본문 그대로**를 기한 확인 경로로
 * 서명해 보낸다(실행 경로가 아니다 — 서명은 대역이 독립 검증).
 */
class PaymentOpsDecisionGoldenSpec : FunSpec({

    StubPaymentOpsServer.start()
    val mapper = JsonMapper.builder().build()
    val api = mapper.readTree(PaymentOpsDecisionGoldenSpec::class.java.getResource("/contracts/admin-payment-ops-api-v1.golden.json")!!.readText(Charsets.UTF_8))
    val settings = PaymentOpsSettings(
        "http://127.0.0.1:${StubPaymentOpsServer.PORT}", "adm-golden-k1", "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx", Duration.ofSeconds(1),
        Duration.ofSeconds(2), PaymentOpsSettings.Permits(4, 4, 4), 5, Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofHours(24),
        Duration.ofMinutes(5), 50, Duration.ofMinutes(2), 10, Duration.ofDays(1),
    )
    val gateway = HttpPaymentOpsGateway(settings, AdminCallSigner(settings.keyId, settings.signingKey())) { Instant.now() }
    val placeholders = mapOf("CANCELLATION_ID" to "\"pcn_01JC000000000000000000DG01\"", "TIMESTAMP" to "\"2026-10-27T01:02:03.456Z\"", "CONTENT_HASH" to "\"opd1:${"0".repeat(64)}\"")
    fun body(node: tools.jackson.databind.JsonNode): String {
        var text = mapper.writeValueAsString(node)
        placeholders.forEach { (name, value) -> text = text.replace("{\"\$placeholder\":\"$name\"}", value) }
        return text
    }
    var seq = 0
    fun approvalId() = "apr_01JC00000000000000000DG" + (++seq).toString().padStart(3, '0')
    fun state(r: DecisionResult): String = when (r) {
        is DecisionResult.Recorded -> if (r.decision == "STARTED") "EXECUTED" else "OWNER_REJECTED"
        DecisionResult.None -> "UNCHANGED"
        is DecisionResult.Unavailable -> "UNAVAILABLE"
    }

    test("decisionResponses (3): STARTED → EXECUTED, REJECTED → OWNER_REJECTED, NONE → unchanged; the common content fields are parsed (L-b)") {
        val cases = api["decisionResponses"].toList()
        cases.associate { c ->
            val id = approvalId()
            StubPaymentOpsServer.decision(id, StubPaymentOpsServer.Reply(c["httpStatus"].asInt(), body(c["body"])))
            c["name"].asString() to state(gateway.decision(id))
        } shouldBe cases.associate { it["name"].asString() to it["expectedAdminState"].asString() }
        val id = approvalId()
        StubPaymentOpsServer.decision(id, StubPaymentOpsServer.Reply(200, body(cases.first()["body"])))
        gateway.decision(id).shouldBeInstanceOf<DecisionResult.Recorded>().let { listOf(it.cancellationId, it.content, it.confirmed) } shouldBe listOf(
            "pcn_01JC000000000000000000DG01",
            DecisionContent("pay_01JC0000000000000000000001", 7000, "KRW", "adm_01JC00000000000000000000R1", "adm_01JC00000000000000000000A1", "oct1:" + "0".repeat(64)),
            false,
        )
    }

    test("I-22 Demo backoff: 40 s × 2^(n−1) (I-113 — S7 review L-1 Demo initial 40 s), capped at 10 minutes, no attempt limit") {
        val demo = PaymentOpsSettings(
            "http://127.0.0.1:${StubPaymentOpsServer.PORT}", "adm-golden-k1", "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx", Duration.ofSeconds(2),
            Duration.ofSeconds(30), PaymentOpsSettings.Permits(4, 4, 4), 30, Duration.ofHours(24), Duration.ofSeconds(40), Duration.ofMinutes(10), Duration.ofHours(24),
            Duration.ofMinutes(5), 50, Duration.ofMinutes(2), 10, Duration.ofDays(1),
        )
        val metrics = object : com.paycore.admin.paymentops.application.required.PaymentOpsMetrics {
            override fun call(operation: String, result: String) = Unit
            override fun lookupRateLimited() = Unit
        }
        val policy = com.paycore.admin.paymentops.application.PaymentOpsService(gateway, demo, metrics)
        listOf(1, 2, 3, 4, 5, 6, 7, 50, 10_000).map { policy.backoff(it) } shouldBe listOf(40L, 80, 160, 320, 600, 600, 600, 600, 600).map(Duration::ofSeconds)
        listOf(demo.staleAfter, demo.deadlineSkew) shouldBe listOf(Duration.ofSeconds(62), Duration.ofMinutes(5))
    }

    test("deadlineResponses (4): expected ADMIN state, confirmed flag, and the Payment block is released exactly when the state is terminal") {
        val cases = api["deadlineResponses"].toList()
        cases.associate { c ->
            val id = approvalId()
            StubPaymentOpsServer.deadline(id, StubPaymentOpsServer.Reply(200, body(c["body"])))
            val r = gateway.confirmDeadline(id, "{\"approvalId\":\"$id\"}".toByteArray())
            val s = state(r)
            c["name"].asString() to Triple(s, (r as? DecisionResult.Recorded)?.confirmed ?: false, s == "EXECUTED" || s == "OWNER_REJECTED")
        } shouldBe cases.associate {
            it["name"].asString() to Triple(it["expectedAdminState"].asString(), it["body"]["confirmed"].asBoolean(), it["releasesPaymentBlock"].asBoolean())
        }
    }

    test("S7 listPage (golden ③): the decision list page parses into items with content, recordedAt and origin, plus the stable nextWatermark") {
        var text = body(api["listPage"])
        text = text.replace("{\"\$placeholder\":\"SEQ\"}", "41").replace("{\"\$placeholder\":\"WATERMARK\"}", "41")
        StubPaymentOpsServer.decisionList(40_000_000L, StubPaymentOpsServer.Reply(200, text))
        val page = gateway.decisionsAfter(40_000_000L).shouldBeInstanceOf<com.paycore.admin.paymentops.application.provided.DecisionPage.Page>()
        page.nextWatermark shouldBe 41L
        page.items.single().let { listOf(it.seq, it.approvalId, it.decision, it.code, it.cancellationId, it.content, it.recordedAt, it.origin) } shouldBe listOf(
            41L, "apr_01JC0000000000000000000001", "REJECTED", "PAYMENT_NOT_FOUND", null,
            DecisionContent("pay_01JC0000000000000000000001", 7000, "KRW", "adm_01JC00000000000000000000R1", "adm_01JC00000000000000000000A1", "oct1:" + "0".repeat(64)),
            Instant.parse("2026-10-27T01:02:03.456Z"), "EXECUTION_COMMAND",
        )
        StubPaymentOpsServer.decisionList(40_000_001L, StubPaymentOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
        gateway.decisionsAfter(40_000_001L)::class.simpleName shouldBe "Unavailable"
        StubPaymentOpsServer.requestsForPath("/internal/v1/admin/operator-decisions/after/40000000").single().signatureValid shouldBe true
    }

    test("the deadline confirmation carries the stored body bytes on the deadline path with a valid signature; failures are Unavailable") {
        val id = approvalId()
        val stored = "{\"approvalId\":\"$id\",\"reason\":\"한글\"}".toByteArray()
        gateway.confirmDeadline(id, stored) shouldBe DecisionResult.None
        val r = StubPaymentOpsServer.requestsForPath("/internal/v1/admin/operator-cancellations/$id/deadline-confirmation").single()
        listOf(r.method, r.signatureValid, r.body.toList()) shouldBe listOf("POST", true, stored.toList())
        val down = approvalId()
        StubPaymentOpsServer.decision(down, StubPaymentOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
        StubPaymentOpsServer.deadline(down, StubPaymentOpsServer.Reply(200, "{\"decision\":\"MAYBE\"}"))
        listOf(gateway.decision(down), gateway.confirmDeadline(down, stored)).map { it::class.simpleName } shouldBe listOf("Unavailable", "Unavailable")
    }
})
