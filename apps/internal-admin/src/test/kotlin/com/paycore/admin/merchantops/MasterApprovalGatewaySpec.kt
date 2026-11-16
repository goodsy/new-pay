package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.adapter.integration.HttpMasterOpsGateway
import com.paycore.admin.merchantops.adapter.integration.MasterCallSigner
import com.paycore.admin.merchantops.adapter.integration.MasterOpsSettings
import com.paycore.admin.merchantops.application.provided.MerchantApprovalOperation
import com.paycore.admin.merchantops.application.provided.MerchantApprovalResult
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

/**
 * ADMIN → MASTER 2인 승인 채널(MERCHANT-OPS-002 B2-2 §9.4·§9.5 C-28 — 실제 HTTP 대역, 서명은 대역이 앱과 독립 검증). 저장 바이트 그대로 POST, 응답 모양 fail-closed, 상태별 분류,
 * 결정 조회 NONE/DECIDED, 기한 확인 NOT_YET_DUE, DB TX 안 호출 거부. 상세 Revision에 `registeredBy`가 있어야 해석된다(필드 집합 정확 일치).
 */
class MasterApprovalGatewaySpec : BehaviorSpec({

    StubMasterOpsServer.start()
    fun settings(read: Duration = Duration.ofSeconds(2)) = MasterOpsSettings(
        "http://127.0.0.1:${StubMasterOpsServer.PORT}", StubMasterOpsServer.KEY_ID, StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), read, 2, 30, 10, 2, 20, 2, Duration.ofMillis(100), // TXN-LIST-ENRICH-001: 라벨 permit·timeout(시험 값)
    )
    val gateway = settings().let { s -> HttpMasterOpsGateway(s, MasterCallSigner(s.keyId, s.signingKey())) { Instant.now() } }
    val op = MerchantApprovalOperation.APPROVE_CONTRACT_REVISION
    val body = """{"approvalId":"apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","stored":"bytes"}""".toByteArray()
    val ref = "ctr_01JCCT00000000000000000CT1#2"
    val fp = "mop1:" + "a".repeat(64)
    fun execute(reply: Reply): MerchantApprovalResult {
        StubMasterOpsServer.reply(StubMasterOpsServer.CONTRACT_REVISION_APPROVALS, reply)
        return gateway.approval(op, body, deadline = false)
    }
    fun deadline(reply: Reply): MerchantApprovalResult {
        StubMasterOpsServer.reply(StubMasterOpsServer.DEADLINE_CONFIRMATIONS, reply)
        return gateway.approval(op, body, deadline = true)
    }
    fun decision(reply: Reply): MerchantApprovalResult {
        StubMasterOpsServer.reply(StubMasterOpsServer.APPROVAL_DECISIONS, reply)
        return gateway.approvalDecision("apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H")
    }

    Given("AG-1 execution: the stored bytes are POSTed unchanged with a valid v1 signature") {
        Then("200 SUCCEEDED / REJECTED (replay included) are decided; a rejection carrying a reference, a non-ctr#n reference or an extra field is unreadable (Unknown)") {
            execute(Reply(200, StubMasterOpsServer.succeeded(ref))) shouldBe MerchantApprovalResult.Decided(true, ref, null, false)
            val sent = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CONTRACT_REVISION_APPROVALS).last()
            listOf(sent.method, sent.signatureValid, sent.body.contentEquals(body)) shouldBe listOf("POST", true, true)
            execute(Reply(200, StubMasterOpsServer.rejected("REVISION_ALREADY_APPROVED", replayed = true))) shouldBe
                MerchantApprovalResult.Decided(false, null, "REVISION_ALREADY_APPROVED", true)
            listOf(
                execute(Reply(200, """{"outcome":"REJECTED","rejection":"X","reference":"$ref","replayed":false}""")),
                execute(Reply(200, StubMasterOpsServer.succeeded("ctr_01JCCT00000000000000000CT1#0"))),
                execute(Reply(200, StubMasterOpsServer.succeeded("mid_01JCCT00000000000000000CT1"))),
                execute(Reply(200, """{"outcome":"SUCCEEDED","reference":"$ref","replayed":false,"extra":1}""")),
                execute(Reply(200, """{"decision":"NOT_YET_DUE"}""")),
            ) shouldBe List(5) { MerchantApprovalResult.Unknown("RESPONSE_UNREADABLE", alert = false) }
        }
        Then("C-28 mapping: 409 key reuse, 400/413 malformed, 401/404 unknown with alert, 503 codes and timeouts unknown") {
            listOf(
                execute(Reply(409, """{"error":"IDEMPOTENCY_KEY_REUSED"}""")),
                execute(Reply(400, """{"error":"REQUEST_MALFORMED"}""")),
                execute(Reply(413, "")),
                execute(Reply(401, """{"error":"UNAUTHENTICATED"}""")),
                execute(Reply(404, """{"error":"NOT_FOUND"}""")),
                execute(Reply(503, """{"error":"REQUEST_IN_PROGRESS"}""")),
                execute(Reply(503, """{"error":"OUTCOME_UNKNOWN"}""")),
                execute(Reply(503, """{"error":"ADMIN_WRITE_BUSY"}""")),
                execute(Reply(200, StubMasterOpsServer.succeeded(ref), delayMillis = 2_600)),
            ) shouldBe listOf(
                MerchantApprovalResult.KeyReused, MerchantApprovalResult.Malformed("REQUEST_MALFORMED"), MerchantApprovalResult.Malformed("PAYLOAD_TOO_LARGE"),
                MerchantApprovalResult.Unknown("MASTER_UNAUTHORIZED", alert = true), MerchantApprovalResult.Unknown("MASTER_NOT_FOUND", alert = true),
                MerchantApprovalResult.Unknown("REQUEST_IN_PROGRESS", alert = false), MerchantApprovalResult.Unknown("OUTCOME_UNKNOWN", alert = false),
                MerchantApprovalResult.Unknown("ADMIN_WRITE_BUSY", alert = false), MerchantApprovalResult.Unknown("TIMEOUT", alert = false),
            )
        }
        Then("inside a DB transaction the gateway refuses before calling") {
            val before = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CONTRACT_REVISION_APPROVALS).size
            TransactionSynchronizationManager.setActualTransactionActive(true)
            try {
                shouldThrow<IllegalStateException> { gateway.approval(op, body, deadline = false) }
                shouldThrow<IllegalStateException> { gateway.approvalDecision("apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H") }
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CONTRACT_REVISION_APPROVALS).size shouldBe before
        }
    }

    Given("AG-2 deadline confirmation and decision lookup") {
        Then("deadline confirmation sends the same stored bytes; NOT_YET_DUE is its own result; otherwise the execution shape") {
            deadline(Reply(200, """{"decision":"NOT_YET_DUE"}""")) shouldBe MerchantApprovalResult.NotYetDue
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.DEADLINE_CONFIRMATIONS).last().body.contentEquals(body) shouldBe true
            deadline(Reply(200, StubMasterOpsServer.rejected("APPROVAL_EXPIRED"))) shouldBe MerchantApprovalResult.Decided(false, null, "APPROVAL_EXPIRED", false)
            deadline(Reply(409, """{"error":"IDEMPOTENCY_KEY_REUSED"}""")) shouldBe MerchantApprovalResult.KeyReused
            deadline(Reply(200, """{"decision":"LATER"}""")) shouldBe MerchantApprovalResult.Unknown("RESPONSE_UNREADABLE", alert = false)
        }
        Then("decision: body {approvalId, correlationId mop.<id>}; NONE; DECIDED with fingerprint and decidedVia; an unknown shape is unreadable") {
            decision(Reply(200, """{"decision":"NONE"}""")) shouldBe MerchantApprovalResult.None
            String(StubMasterOpsServer.requestsForPath(StubMasterOpsServer.APPROVAL_DECISIONS).last().body) shouldBe
                """{"approvalId":"apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","correlationId":"mop.apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H"}"""
            decision(Reply(200, StubMasterOpsServer.decidedJson(fp, reference = ref))) shouldBe
                MerchantApprovalResult.Decided(true, ref, null, true, "ApproveContractRevision", fp, "EXECUTE")
            decision(Reply(200, StubMasterOpsServer.decidedJson(fp, rejection = "APPROVAL_EXPIRED", decidedVia = "DEADLINE_CONFIRM"))) shouldBe
                MerchantApprovalResult.Decided(false, null, "APPROVAL_EXPIRED", true, "ApproveContractRevision", fp, "DEADLINE_CONFIRM")
            listOf(
                decision(Reply(200, StubMasterOpsServer.decidedJson("oct1:" + "a".repeat(64), reference = ref))),
                decision(Reply(200, StubMasterOpsServer.decidedJson(fp, reference = ref, decidedVia = "MANUAL"))),
                decision(Reply(200, """{"decision":"NONE","approvalId":"x"}""")),
            ) shouldBe List(3) { MerchantApprovalResult.Unknown("RESPONSE_UNREADABLE", alert = false) }
            decision(Reply(503, """{"error":"ADMIN_READ_BUSY"}""")) shouldBe MerchantApprovalResult.Unknown("ADMIN_READ_BUSY", alert = false)
        }
    }

    Given("AG-4 B2-3 first Default MID issuance (§9.4 — first-default-merchant-accounts, reference mid_…)") {
        val mid = "mid_01JCMD00000000000000000001"
        Then("the stored bytes go to first-default-merchant-accounts; only a mid_ reference is a success (a ctr#n is unreadable); the decision lookup accepts IssueFirstDefaultMerchantAccount") {
            val path = "/internal/v1/admin/merchant-operations/first-default-merchant-accounts"
            StubMasterOpsServer.reply(path, Reply(200, StubMasterOpsServer.succeeded(mid)))
            gateway.approval(MerchantApprovalOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, body, deadline = false) shouldBe MerchantApprovalResult.Decided(true, mid, null, false)
            StubMasterOpsServer.requestsForPath(path).last().body.contentEquals(body) shouldBe true
            StubMasterOpsServer.reply(path, Reply(200, StubMasterOpsServer.succeeded(ref)))
            gateway.approval(MerchantApprovalOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, body, deadline = false) shouldBe MerchantApprovalResult.Unknown("RESPONSE_UNREADABLE", alert = false)
            // 계약 승인 경로는 mid_를 성공으로 받지 않는다.
            execute(Reply(200, StubMasterOpsServer.succeeded(mid))) shouldBe MerchantApprovalResult.Unknown("RESPONSE_UNREADABLE", alert = false)
            decision(Reply(200, StubMasterOpsServer.decidedJson(fp, reference = mid, operation = "IssueFirstDefaultMerchantAccount"))) shouldBe
                MerchantApprovalResult.Decided(true, mid, null, true, "IssueFirstDefaultMerchantAccount", fp, "EXECUTE")
            decision(Reply(200, StubMasterOpsServer.decidedJson(fp, reference = ref, operation = "IssueFirstDefaultMerchantAccount"))) shouldBe
                MerchantApprovalResult.Unknown("RESPONSE_UNREADABLE", alert = false)
            StubMasterOpsServer.reply(StubMasterOpsServer.DEADLINE_CONFIRMATIONS, Reply(200, StubMasterOpsServer.succeeded(mid, replayed = true)))
            gateway.approval(MerchantApprovalOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, body, deadline = true) shouldBe MerchantApprovalResult.Decided(true, mid, null, true)
        }
    }

    Given("AG-5 review n-1: the ADMIN execution window must not exceed the mirrored MASTER max-execution-window (startup check, no default)") {
        Then("execution-window ≤ master-max-execution-window starts; larger or missing refuses to start") {
            fun settings(window: String, mirror: String?) = com.paycore.admin.merchantops.adapter.integration.MerchantApprovalSettings(
                Duration.parse(window), Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofMinutes(30), mirror?.let(Duration::parse),
            )
            settings("PT1H", "PT24H").policy(Duration.ofSeconds(1), Duration.ofSeconds(2)).executionWindow shouldBe Duration.ofHours(1)
            settings("PT24H", "PT24H").masterMaxExecutionWindow shouldBe Duration.ofHours(24)
            // B2-3 리뷰 n-2: 기동 로그·설정 덤프에서 거울 값이 보인다.
            settings("PT1H", "PT24H").toString().contains("masterMaxExecutionWindow=PT24H") shouldBe true
            shouldThrow<IllegalArgumentException> { settings("PT25H", "PT24H") }
            shouldThrow<IllegalArgumentException> { settings("PT1H", null) }
        }
    }

    Given("AG-3 the MASTER detail now carries registeredBy per revision (exact field set — B2-2)") {
        Then("with registeredBy it is read; without it the detail is unreadable (fail-closed)") {
            val id = "mer_01JCAG00000000000000000001"
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(id), Reply(200, StubMasterOpsServer.detailJson(id)))
            gateway.detail(id).shouldBeInstanceOf<MasterCallResult.Found>()
            StubMasterOpsServer.reply(
                StubMasterOpsServer.merchantPath(id),
                Reply(200, StubMasterOpsServer.detailJson(id).replace(""","registeredBy":"${StubMasterOpsServer.REGISTRANT}"""", "")),
            )
            gateway.detail(id) shouldBe MasterCallResult.Unavailable("RESPONSE_UNREADABLE")
        }
    }
})
