package com.paycore.admin.financialops

import com.paycore.admin.financialops.adapter.integration.FinancialCallSigner
import com.paycore.admin.financialops.adapter.integration.FinancialOpsConfiguration
import com.paycore.admin.financialops.adapter.integration.FinancialOpsSettings
import com.paycore.admin.financialops.adapter.integration.HttpFinancialOpsGateway
import com.paycore.admin.financialops.application.AcquiringOpsService
import com.paycore.admin.financialops.application.provided.AcquiringOpsResult
import com.paycore.admin.financialops.application.provided.ReacquisitionTargetsResult
import com.paycore.admin.support.StubFinancialOpsServer
import com.paycore.admin.support.StubFinancialOpsServer.Reply
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

/**
 * FINANCIAL 매입 조작 채널 클라이언트(ACQ-ADMIN-001 B1-A — D-ACQA-001 "조작 전용 키 따로"). 실제 HTTP 대역([StubFinancialOpsServer] — 서명을 앱과 독립으로 조회 키·조작 키 각각 검증).
 * - 조작 키는 조회 키와 kid·바이트 모두 달라야 기동(같으면 조회 키 유출이 매입 상태 변경 권한이 된다). 다른 소유 서비스 키와도 달라야 한다. LIVE는 공개 시험 조작 키 거부.
 * - 미리 보기는 조회 키, 실행·결정 조회·기한 확인은 조작 키로만 서명한다.
 * - 200 본문은 허용 모양만(모르는 필드·결정 모양 위반 → 결과 불명확), 409 APPROVAL_CONFLICT → 무결성 플래그, 400 → 형식 거절, 503 → 불명확.
 */
class AcquiringOpsGatewaySpec : BehaviorSpec({

    StubFinancialOpsServer.start()
    val base = "http://127.0.0.1:${StubFinancialOpsServer.PORT}"
    fun settings(opKid: String = StubFinancialOpsServer.OP_KEY_ID, opKey: String = StubFinancialOpsServer.OP_KEY_BASE64) = FinancialOpsSettings(
        base, StubFinancialOpsServer.KEY_ID, StubFinancialOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(2), 2, 30, opKid, opKey, 1, Duration.ofHours(24),
    )
    fun service(s: FinancialOpsSettings = settings()) =
        AcquiringOpsService(HttpFinancialOpsGateway(s, FinancialCallSigner(s.keyId, s.signingKey()), FinancialCallSigner(s.operationKeyId, s.operationSigningKey())) { Instant.now() })
    val fileId = "ACQ.GW${System.nanoTime() % 1_000_000}.TEST.20261105.001"
    val body = """{"approvalId":"apr_GW1","originalFileId":"$fileId","items":[{"seq":1,"expectedGeneration":0,"expectedStatus":"REJECTED"}],"requestedBy":"adm_r",""" +
        """"approvedBy":"adm_a","reasonCode":"INSTITUTION_REQUESTED","evidenceRef":"INQ-1","approvedAt":"2026-11-07T09:00:00.000Z","executeBefore":"2026-11-07T10:00:00.000Z",""" +
        """"correlationId":"c1"}"""

    Given("GW-1 startup guard (D-ACQA-001)") {
        Then("the operation key must differ from the read key by kid and by bytes, and from the PAYMENT / MASTER channel keys; LIVE refuses the public test operation key") {
            FinancialOpsConfiguration.startupGuard("", "DEMO", settings(), emptyList())
            listOf(
                { FinancialOpsConfiguration.startupGuard("", "DEMO", settings(opKid = StubFinancialOpsServer.KEY_ID), emptyList()) },
                { FinancialOpsConfiguration.startupGuard("", "DEMO", settings(opKey = StubFinancialOpsServer.KEY_BASE64), emptyList()) },
                { FinancialOpsConfiguration.startupGuard("", "DEMO", settings(), listOf("pops-k1" to StubFinancialOpsServer.OP_KEY_BASE64)) },
                { FinancialOpsConfiguration.startupGuard("", "DEMO", settings(), listOf(StubFinancialOpsServer.OP_KEY_ID to "bGl2ZS1zZWNyZXQta2V5LW5vdC1hLWRlbW8ta2V5LTAwMDE=")) },
            ).forEach { guard ->
                val e = shouldThrow<IllegalStateException> { guard() }
                e.message!! shouldNotContain StubFinancialOpsServer.OP_KEY_BASE64
                e.message!! shouldNotContain StubFinancialOpsServer.KEY_BASE64
            }
            val live = settings().let {
                FinancialOpsSettings(base, "live-read", "bGl2ZS1zZWNyZXQta2V5LW5vdC1hLWRlbW8ta2V5LTAwMDE=", Duration.ofSeconds(1), Duration.ofSeconds(2), 2, 30,
                    StubFinancialOpsServer.OP_KEY_ID, StubFinancialOpsServer.OP_KEY_BASE64, 1, Duration.ofHours(24))
            }
            shouldThrow<IllegalStateException> { FinancialOpsConfiguration.startupGuard("", "LIVE", live, emptyList()) }
            shouldThrow<IllegalArgumentException> {
                FinancialOpsSettings(base, "k", StubFinancialOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1)
            }
            settings().toString() shouldNotContain StubFinancialOpsServer.OP_KEY_BASE64
        }
    }

    Given("GW-2 which key signs which path") {
        Then("the preview is signed with the READ key; execute, decision lookup and deadline confirmation with the OPERATION key only") {
            StubFinancialOpsServer.reply(StubFinancialOpsServer.targetsPath(fileId), Reply(200, StubFinancialOpsServer.targetsJson(fileId, StubFinancialOpsServer.targetItemJson(1))))
            StubFinancialOpsServer.replyWith(StubFinancialOpsServer.EXECUTE) { Reply(200, StubFinancialOpsServer.decisionJson(it, "NOT_REQUIRED")) }
            StubFinancialOpsServer.reply(StubFinancialOpsServer.decisionPath("apr_GW1"), Reply(200, "{\"decision\":\"NONE\",\"approvalId\":\"apr_GW1\"}"))
            StubFinancialOpsServer.reply(StubFinancialOpsServer.deadlinePath("apr_GW1"), Reply(200, "{\"decision\":\"NONE\",\"confirmed\":false}"))
            val s = service()
            s.targets(fileId, listOf(1)).shouldBeInstanceOf<ReacquisitionTargetsResult.Found>().view.items.single().expectedNetMinor shouldBe 10_000L
            s.execute("apr_GW1", body.toByteArray()).shouldBeInstanceOf<AcquiringOpsResult.Decided>().decision.decision shouldBe "NOT_REQUIRED"
            s.decision("apr_GW1") shouldBe AcquiringOpsResult.None
            s.confirmDeadline("apr_GW1", body.toByteArray()) shouldBe AcquiringOpsResult.None
            fun last(path: String) = StubFinancialOpsServer.requestsForPath(path).last().let { it.signatureValid to it.operationSignatureValid }
            listOf(
                last(StubFinancialOpsServer.targetsPath(fileId)), last(StubFinancialOpsServer.EXECUTE), last(StubFinancialOpsServer.decisionPath("apr_GW1")),
                last(StubFinancialOpsServer.deadlinePath("apr_GW1")),
            ) shouldBe listOf(true to false, false to true, false to true, false to true)
        }
    }

    Given("GW-3 response classification (FINANCIAL I-16 — only committed decisions are final)") {
        Then("unknown field / EXECUTED without a file → unreadable (unknown); 409 APPROVAL_CONFLICT → integrity flag; 400 → malformed; 401 → alert; 503 → unknown; a preview for other seqs → unavailable") {
            val s = service()
            fun execute(reply: (ByteArray) -> Reply): AcquiringOpsResult {
                StubFinancialOpsServer.replyWith(StubFinancialOpsServer.EXECUTE, reply)
                return s.execute("apr_GW1", body.toByteArray())
            }
            listOf(
                execute { Reply(200, StubFinancialOpsServer.decisionJson(it, "NOT_REQUIRED").removeSuffix("}") + ",\"amountMinor\":1}") },
                execute { Reply(200, StubFinancialOpsServer.decisionJson(it, "EXECUTED")) },
                execute { Reply(409, "{\"error\":\"APPROVAL_CONFLICT\"}") },
                execute { Reply(400, "{\"error\":\"REQUEST_MALFORMED\"}") },
                execute { Reply(401, "{\"error\":\"UNAUTHENTICATED\"}") },
                execute { Reply(503, "{\"error\":\"ADMIN_OPERATION_BUSY\"}") },
            ) shouldBe listOf(
                AcquiringOpsResult.Unknown("RESPONSE_UNREADABLE", null, false), AcquiringOpsResult.Unknown("RESPONSE_UNREADABLE", null, false),
                AcquiringOpsResult.Unknown("APPROVAL_CONFLICT", "APPROVAL_CONFLICT", true), AcquiringOpsResult.Malformed("REQUEST_MALFORMED"),
                AcquiringOpsResult.Unknown("UNAUTHENTICATED", null, true), AcquiringOpsResult.Unknown("UNAVAILABLE_ADMIN_OPERATION_BUSY", null, false),
            )
            StubFinancialOpsServer.reply(StubFinancialOpsServer.targetsPath(fileId), Reply(200, StubFinancialOpsServer.targetsJson(fileId, StubFinancialOpsServer.targetItemJson(2))))
            s.targets(fileId, listOf(1)) shouldBe ReacquisitionTargetsResult.Unavailable("RESPONSE_UNREADABLE")
        }
        Then("B1-A review n-3: an execute POST answered with another approval's decision is not this approval's result (request-response pairing) → unreadable (unknown, resolved only by lookup)") {
            val s = service()
            StubFinancialOpsServer.replyWith(StubFinancialOpsServer.EXECUTE) {
                Reply(200, StubFinancialOpsServer.decisionJson(it, "NOT_REQUIRED").replace("\"approvalId\":\"apr_GW1\"", "\"approvalId\":\"apr_GW2\""))
            }
            s.execute("apr_GW1", body.toByteArray()) shouldBe AcquiringOpsResult.Unknown("RESPONSE_UNREADABLE", null, false)
            StubFinancialOpsServer.replyWith(StubFinancialOpsServer.EXECUTE) { Reply(200, StubFinancialOpsServer.decisionJson(it, "NOT_REQUIRED")) }
            s.execute("apr_GW1", body.toByteArray()).shouldBeInstanceOf<AcquiringOpsResult.Decided>().decision.approvalId shouldBe "apr_GW1"
        }
    }
})
