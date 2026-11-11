package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.adapter.integration.HttpMasterOpsGateway
import com.paycore.admin.merchantops.adapter.integration.MasterCallSigner
import com.paycore.admin.merchantops.adapter.integration.MasterOpsSettings
import com.paycore.admin.merchantops.application.provided.MerchantApprovalOperation
import com.paycore.admin.merchantops.application.provided.MerchantApprovalResult
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MasterCommand
import com.paycore.admin.merchantops.application.required.MasterCommandKind
import com.paycore.admin.merchantops.application.required.MasterCommandResult
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

/**
 * MERCHANT-OPS-002 B3-1 `MasterResponseShape`(minor-4)와 MASTER 상세 확장(실제 HTTP 대역 — 서명은 대역이 독립 검증).
 * - 승인 실행·기한 확인: operation별 성공 reference 문법(개통 `act_…`, Site 심사 `site_…#n`) — 다른 문법이면 해석 불가(Unknown).
 * - 결정 조회: `operation` 허용 목록에 B3-1 두 operation, 목록 밖 operation·문법 불일치·operation 교차는 해석 불가(fail-closed).
 * - 1인 명령: 개통 등록 `act_…`, Site 심사 거절 `site_…#n`(`#`는 이 종류에만), 거절에 reference 없음(C-12), 근거 참조 없으면 키 없음.
 * - 상세: `sites[].registeredBy`·`paymentMethods[].registeredBy`·`siteConfirmations[]`가 있어야 해석된다(필드 집합 정확 일치).
 */
class MasterApprovalGatewayB3Spec : BehaviorSpec({

    StubMasterOpsServer.start()
    val settings = MasterOpsSettings(
        "http://127.0.0.1:${StubMasterOpsServer.PORT}", StubMasterOpsServer.KEY_ID, StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(2), 2, 30, 10, 2, 20,
    )
    val gateway = HttpMasterOpsGateway(settings, MasterCallSigner(settings.keyId, settings.signingKey())) { Instant.now() }
    val body = """{"approvalId":"apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","stored":"bytes"}""".toByteArray()
    val act = "act_01JCAC00000000000000000AC1"
    val site = "site_01JCST00000000000000000ST1"
    val siteDecision = "$site#1"
    val fp = "mop1:" + "b".repeat(64)
    val activationOp = MerchantApprovalOperation.APPROVE_PAYMENT_METHOD_ACTIVATION
    val siteOp = MerchantApprovalOperation.DECIDE_SITE_REVIEW
    val unreadable = MerchantApprovalResult.Unknown("RESPONSE_UNREADABLE", alert = false)
    fun execute(op: MerchantApprovalOperation, path: String, reply: Reply): MerchantApprovalResult {
        StubMasterOpsServer.reply(path, reply)
        return gateway.approval(op, body, deadline = false)
    }
    fun decision(reply: Reply): MerchantApprovalResult {
        StubMasterOpsServer.reply(StubMasterOpsServer.APPROVAL_DECISIONS, reply)
        return gateway.approvalDecision("apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H")
    }

    Given("AG-B3-1 execution paths and per-operation success grammar") {
        Then("activation: stored bytes to payment-method-activation-approvals; only act_ is a success (site#n, mid_, ctr#n unreadable)") {
            val path = StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS
            execute(activationOp, path, Reply(200, StubMasterOpsServer.succeeded(act))) shouldBe MerchantApprovalResult.Decided(true, act, null, false)
            StubMasterOpsServer.requestsForPath(path).last().let { listOf(it.method, it.signatureValid, it.body.contentEquals(body)) } shouldBe listOf("POST", true, true)
            execute(activationOp, path, Reply(200, StubMasterOpsServer.rejected("PRIMARY_SITE_MISMATCH"))) shouldBe MerchantApprovalResult.Decided(false, null, "PRIMARY_SITE_MISMATCH", false)
            listOf(siteDecision, "mid_01JCAC00000000000000000AC1", "ctr_01JCAC00000000000000000AC1#2", "act_01jcac00000000000000000ac1").map {
                execute(activationOp, path, Reply(200, StubMasterOpsServer.succeeded(it)))
            } shouldBe List(4) { unreadable }
        }
        Then("site review: stored bytes to site-review-approvals; only site_…#n (n 1..10 digits, no leading 0) is a success") {
            val path = StubMasterOpsServer.SITE_REVIEW_APPROVALS
            execute(siteOp, path, Reply(200, StubMasterOpsServer.succeeded(siteDecision))) shouldBe MerchantApprovalResult.Decided(true, siteDecision, null, false)
            execute(siteOp, path, Reply(200, StubMasterOpsServer.succeeded("$site#9999999999"))) shouldBe MerchantApprovalResult.Decided(true, "$site#9999999999", null, false)
            listOf(site, "$site#0", "$site#01", "$site#12345678901", act).map { execute(siteOp, path, Reply(200, StubMasterOpsServer.succeeded(it))) } shouldBe List(5) { unreadable }
        }
        Then("deadline confirmation uses the same per-operation grammar (stored bytes to approval-deadline-confirmations)") {
            StubMasterOpsServer.reply(StubMasterOpsServer.DEADLINE_CONFIRMATIONS, Reply(200, StubMasterOpsServer.succeeded(act, replayed = true)))
            gateway.approval(activationOp, body, deadline = true) shouldBe MerchantApprovalResult.Decided(true, act, null, true)
            gateway.approval(siteOp, body, deadline = true) shouldBe unreadable
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.DEADLINE_CONFIRMATIONS).last().body.contentEquals(body) shouldBe true
        }
    }

    Given("AG-B3-2 decision lookup: operation allow-list and grammar (minor-4)") {
        Then("the two B3-1 operations are read with their own grammar; rejections name them too") {
            decision(Reply(200, StubMasterOpsServer.decidedJson(fp, reference = act, operation = "ApprovePaymentMethodActivation"))) shouldBe
                MerchantApprovalResult.Decided(true, act, null, true, "ApprovePaymentMethodActivation", fp, "EXECUTE")
            decision(Reply(200, StubMasterOpsServer.decidedJson(fp, reference = siteDecision, operation = "DecideSiteReview", decidedVia = "DEADLINE_CONFIRM"))) shouldBe
                MerchantApprovalResult.Decided(true, siteDecision, null, true, "DecideSiteReview", fp, "DEADLINE_CONFIRM")
            decision(Reply(200, StubMasterOpsServer.decidedJson(fp, rejection = "PRIMARY_SITE_MISMATCH", operation = "ApprovePaymentMethodActivation"))) shouldBe
                MerchantApprovalResult.Decided(false, null, "PRIMARY_SITE_MISMATCH", true, "ApprovePaymentMethodActivation", fp, "EXECUTE")
            decision(Reply(200, StubMasterOpsServer.decidedJson(fp, rejection = "SITE_REVIEW_TRANSITION_NOT_ALLOWED", operation = "DecideSiteReview"))) shouldBe
                MerchantApprovalResult.Decided(false, null, "SITE_REVIEW_TRANSITION_NOT_ALLOWED", true, "DecideSiteReview", fp, "EXECUTE")
        }
        Then("cross acceptance is refused: act_ under DecideSiteReview, site#n under ApprovePaymentMethodActivation; an operation outside the list (B3-2..4, 1-person RejectSiteReview) is unreadable") {
            listOf(
                decision(Reply(200, StubMasterOpsServer.decidedJson(fp, reference = act, operation = "DecideSiteReview"))),
                decision(Reply(200, StubMasterOpsServer.decidedJson(fp, reference = siteDecision, operation = "ApprovePaymentMethodActivation"))),
                decision(Reply(200, StubMasterOpsServer.decidedJson(fp, reference = "cur_01JCAC00000000000000000AC1", operation = "EnableCurrency"))),
                decision(Reply(200, StubMasterOpsServer.decidedJson(fp, rejection = "APPROVAL_EXPIRED", operation = "EnableCurrency"))),
                decision(Reply(200, StubMasterOpsServer.decidedJson(fp, reference = siteDecision, operation = "RejectSiteReview"))),
                decision(Reply(200, StubMasterOpsServer.decidedJson(fp, rejection = "APPROVAL_EXPIRED", operation = "RejectSiteReview"))),
            ) shouldBe List(6) { unreadable }
        }
    }

    Given("AG-B3-3 1-person commands (B1 channel — response shape per kind)") {
        fun command(kind: MasterCommandKind, fields: Map<String, Any>) =
            MasterCommand(kind, "mo1.01JC00000000000000000000V1.${"a".repeat(32)}", "admin:adm_01JC00000000000000000000V1", "mos.${"a".repeat(32)}", fields)
        val register = command(MasterCommandKind.REGISTER_PAYMENT_METHOD_ACTIVATION, mapOf("merchantAccountId" to "mid_01JCAC00000000000000000AC1", "paymentMethod" to "CARD"))
        val reject = command(MasterCommandKind.REJECT_SITE_REVIEW, mapOf("merchantSiteId" to site, "reasonCode" to "DOCUMENT_INSUFFICIENT"))
        Then("activation register: body fields in order, act_ success, a site#n or a rejection with reference is unreadable") {
            StubMasterOpsServer.reply(StubMasterOpsServer.PAYMENT_METHOD_ACTIVATIONS, Reply(200, StubMasterOpsServer.succeeded(act)))
            gateway.command(register) shouldBe MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, act, false)
            String(StubMasterOpsServer.requestsForPath(StubMasterOpsServer.PAYMENT_METHOD_ACTIVATIONS).last().body) shouldBe
                """{"idempotencyKey":"mo1.01JC00000000000000000000V1.${"a".repeat(32)}","actor":"admin:adm_01JC00000000000000000000V1","correlationId":"mos.${"a".repeat(32)}",""" +
                """"merchantAccountId":"mid_01JCAC00000000000000000AC1","paymentMethod":"CARD"}"""
            StubMasterOpsServer.reply(StubMasterOpsServer.PAYMENT_METHOD_ACTIVATIONS, Reply(200, StubMasterOpsServer.succeeded(siteDecision)))
            gateway.command(register) shouldBe MasterCommandResult.Unknown("RESPONSE_UNREADABLE")
            StubMasterOpsServer.reply(StubMasterOpsServer.PAYMENT_METHOD_ACTIVATIONS, Reply(200, """{"outcome":"REJECTED","rejection":"ACTIVATION_DUPLICATE","reference":"$act","replayed":false}"""))
            gateway.command(register) shouldBe MasterCommandResult.Unknown("RESPONSE_UNREADABLE")
            StubMasterOpsServer.reply(StubMasterOpsServer.PAYMENT_METHOD_ACTIVATIONS, Reply(200, StubMasterOpsServer.rejected("ACTIVATION_DUPLICATE")))
            gateway.command(register) shouldBe MasterCommandResult.Decided(MasterCommandResult.Outcome.REJECTED, "ACTIVATION_DUPLICATE", null, false)
        }
        Then("site review rejection: no decision field, evidenceRef key only when given; only site_…#n is a success (a bare site_ is unreadable)") {
            StubMasterOpsServer.reply(StubMasterOpsServer.SITE_REVIEW_REJECTIONS, Reply(200, StubMasterOpsServer.succeeded(siteDecision)))
            gateway.command(reject) shouldBe MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, siteDecision, false)
            String(StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SITE_REVIEW_REJECTIONS).last().body).let {
                (it.contains("\"evidenceRef\"") to it.contains("decision")) shouldBe (false to false)
                it.endsWith(""""merchantSiteId":"$site","reasonCode":"DOCUMENT_INSUFFICIENT"}""") shouldBe true
            }
            gateway.command(command(MasterCommandKind.REJECT_SITE_REVIEW, mapOf("merchantSiteId" to site, "reasonCode" to "OTHER_POLICY", "evidenceRef" to "PG-REVIEW/1")))
            String(StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SITE_REVIEW_REJECTIONS).last().body)
                .endsWith(""""merchantSiteId":"$site","reasonCode":"OTHER_POLICY","evidenceRef":"PG-REVIEW/1"}""") shouldBe true
            StubMasterOpsServer.reply(StubMasterOpsServer.SITE_REVIEW_REJECTIONS, Reply(200, StubMasterOpsServer.succeeded(site)))
            gateway.command(reject) shouldBe MasterCommandResult.Unknown("RESPONSE_UNREADABLE")
            // `#n`은 Site 거절 종류에만: Site 등록 명령의 site_…#1은 여전히 해석 불가.
            StubMasterOpsServer.reply(StubMasterOpsServer.SITES, Reply(200, StubMasterOpsServer.succeeded(siteDecision)))
            gateway.command(
                command(MasterCommandKind.REGISTER_MERCHANT_SITE, mapOf("merchantId" to "mer_01JCAC00000000000000000AC1", "channelType" to "WEB", "channelIdentifier" to "a.example.test")),
            ) shouldBe MasterCommandResult.Unknown("RESPONSE_UNREADABLE")
        }
    }

    Given("AG-B3-4 MASTER detail gains sites[].registeredBy, paymentMethods[].registeredBy and accounts[].siteConfirmations[] (exact field set)") {
        Then("with the new fields it is read and passed through; without any one of them (or with an evidence field in a confirmation) it is unreadable") {
            val id = "mer_01JCAG00000000000000000B31"
            fun detail(json: String): MasterCallResult {
                StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(id), Reply(200, json))
                return gateway.detail(id)
            }
            val base = StubMasterOpsServer.detailJson(id)
            val found = detail(base).shouldBeInstanceOf<MasterCallResult.Found>()
            @Suppress("UNCHECKED_CAST")
            val account = (found.document["accounts"] as List<Map<String, Any?>>).single()
            @Suppress("UNCHECKED_CAST")
            (account["siteConfirmations"] as List<Map<String, Any?>>).single() shouldBe mapOf(
                "confirmationId" to "sac_01JCAG00000000000000000B31", "merchantSiteId" to "site_01JCAG00000000000000000B31", "paymentMethod" to "CARD",
                "activationId" to "act_01JCAG00000000000000000B31", "confirmedAt" to "2026-10-09T02:00:00.000Z", "revoked" to false,
            )
            @Suppress("UNCHECKED_CAST")
            ((found.document["sites"] as List<Map<String, Any?>>).single()["registeredBy"]) shouldBe StubMasterOpsServer.SITE_REGISTRANT
            listOf(
                detail(base.replace(""","registeredBy":"${StubMasterOpsServer.SITE_REGISTRANT}"}],"contracts"""", """}],"contracts"""")),
                detail(base.replace(""","registeredAt":"2026-10-09T02:00:00.000Z","registeredBy":"${StubMasterOpsServer.SITE_REGISTRANT}"}""", ""","registeredAt":"2026-10-09T02:00:00.000Z"}""")),
                detail(base.replace(""","siteConfirmations":[""", ""","siteConfirmationz":[""")),
                detail(base.replace(""""revoked":false}""", """"revoked":false,"evidenceRef":"VAN-1"}""")),
                detail(base.replace(""""revoked":false}""", """"revoked":"false"}""")),
            ) shouldBe List(5) { MasterCallResult.Unavailable("RESPONSE_UNREADABLE") }
        }
    }
})
