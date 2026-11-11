package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.str
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovalFingerprint
import com.paycore.master.merchant.application.provided.ApprovedPaymentMethodActivationApprovalCommand
import com.paycore.master.merchant.application.provided.ApprovedSiteReviewApprovalCommand
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.PaymentMethod
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * MERCHANT-OPS-002 §8.3 B3-1-1: B3-1 본문 엄격 해석(단위). 2인 본문은 근거 공통 9 + **`operation` = 경로 operation**(C-39) + 명령 필드, 1인 거절은 결정값 필드가 없다(C-40),
 * 사유 코드는 C-37 목록만. 기한 확인 해석기는 한 본문을 **정확히 한 해석기**만 받는다(minor-4 — B2 본문 ↔ B3 본문 교차 수용 금지).
 * `mop1` **독립 벡터**: 아래 hex는 Python `hashlib.sha256`으로 따로 계산했다(ADMIN 시험이 같은 입력·같은 hex를 쓴다 — C-23).
 */
class ActivationCommandParserSpec : BehaviorSpec({

    val window = ApprovalEvidenceFixtures.DEMO_WINDOW
    val act = "act_01JCAC00000000000000000AC1"
    val site = "site_01JCST00000000000000000ST1"
    val mid = "mid_01JCMD00000000000000000MD1"
    fun approve(body: String) = MerchantApprovalCommandParser.paymentMethodActivationApproval(body.toByteArray(Charsets.UTF_8), window)
    fun review(body: String) = MerchantApprovalCommandParser.siteReviewApproval(body.toByteArray(Charsets.UTF_8), window)
    fun deadline(body: String) = MerchantApprovalCommandParser.deadlineConfirmation(body.toByteArray(Charsets.UTF_8), window)

    Given("the mop1 independent vectors for the B3-1 operations (Python hashlib)") {
        fun vector(operation: AdminOperation, evidenceRef: String, fields: List<String>) = ApprovalFingerprint.mop1(
            operation, "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H", "admin:adm_01JCRQ0000000000000000REQ1", "admin:adm_01JCAP0000000000000000APR1",
            Instant.parse("2026-11-10T01:00:00.000Z"), Instant.parse("2026-11-10T02:00:00.000Z"), evidenceRef, fields,
        )
        Then("ApprovePaymentMethodActivation (activationId, primarySiteId) and DecideSiteReview (merchantSiteId, reasonCode) hash to the fixed hex") {
            vector(AdminOperation.APPROVE_PAYMENT_METHOD_ACTIVATION, "VAN-REPLY/2026-0001", listOf(act, site)) shouldBe
                "mop1:1a914b85d99b5430a22bfa3d24522488915633e964b09a79dfd3cfa62d35893f"
            vector(AdminOperation.APPROVE_PAYMENT_METHOD_ACTIVATION, "VAN-REPLY/2026-0002", listOf(act, site)) shouldBe
                "mop1:f01e3cc54b90c5d8a1c76fa95a2c9b09eb79c9857b3d2ec985dc191d845091f5"
            vector(AdminOperation.DECIDE_SITE_REVIEW, "PG-REVIEW/2026-0001", listOf(site, "REVIEW_PASSED")) shouldBe
                "mop1:3d521c9791ad2a3a71676a65c8e15741dd52c3d400afd88202f1e6db2be12de5"
            vector(AdminOperation.DECIDE_SITE_REVIEW, "PG-REVIEW/2026-0002", listOf(site, "REVIEW_PASSED")) shouldBe
                "mop1:8265c84d652a6520dbb68598f1b24571373318e9c887fca95b4e50531e9363de"
        }
    }

    Given("a payment-method-activation-approvals body") {
        Then("it parses: actor = approver, key mop1.<id>, command fields (activationId, primarySiteId) in §9.14 order; the deadline parser gives the same operation") {
            val a = Approval()
            val body = ActivationBodies.activationApproval(act, site, a)
            val command = approve(body).shouldNotBeNull()
            command.request.actor.value shouldBe a.approvedBy
            command.request.idempotencyKey.value shouldBe "mop1.${a.approvalId}"
            command.activationId.value shouldBe act
            command.primarySiteId.value shouldBe site
            command.commandFields shouldBe listOf(act, site)
            command.evidence.evidenceRef.value shouldBe a.evidenceRef
            deadline(body).shouldBeInstanceOf<ApprovedPaymentMethodActivationApprovalCommand>() shouldBe command
        }

        Then("operation missing or naming another operation, no primarySiteId, a bad id, an actor field or a foreign fingerprint is refused") {
            val a = Approval()
            val fields = ActivationBodies.activationApprovalFields(act, site, a)
            listOf(
                MerchantApprovalBodies.without(fields, "operation"),
                MerchantApprovalBodies.with(fields, "operation", str("DecideSiteReview")),
                MerchantApprovalBodies.with(fields, "operation", str("ResumePaymentMethodActivation")),
                MerchantApprovalBodies.without(fields, "primarySiteId"),
                MerchantApprovalBodies.with(fields, "activationId", str("act_bad")),
                MerchantApprovalBodies.with(fields, "primarySiteId", str("site_bad")),
                MerchantApprovalBodies.with(fields, "actor", str(a.approvedBy)),
                ActivationBodies.activationApproval(act, site, a, fingerprint = a.fingerprint(AdminOperation.APPROVE_PAYMENT_METHOD_ACTIVATION, listOf(act))),
                ActivationBodies.activationApproval(act, site, a, fingerprint = a.fingerprint(AdminOperation.DECIDE_SITE_REVIEW, listOf(act, site))),
            ).forEach { body -> withClue(body) { approve(body).shouldBeNull() } }
        }
    }

    Given("a site-review-approvals body") {
        Then("it parses with the reason code as the decision reason (C-37) and APPROVED is implied (no decision field)") {
            val a = Approval()
            val command = review(ActivationBodies.siteReviewApproval(site, a)).shouldNotBeNull()
            command.merchantSiteId.value shouldBe site
            command.reason.value shouldBe ActivationBodies.APPROVE_REASON
            command.commandFields shouldBe listOf(site, ActivationBodies.APPROVE_REASON)
            deadline(ActivationBodies.siteReviewApproval(site, a)).shouldBeInstanceOf<ApprovedSiteReviewApprovalCommand>() shouldBe command
        }

        Then("a reason code outside the approval list, a rejection code, a decision field or free text is refused") {
            val a = Approval()
            val fields = ActivationBodies.siteReviewApprovalFields(site, a)
            listOf(
                ActivationBodies.siteReviewApproval(site, a, reasonCode = "OTHER_POLICY"),
                ActivationBodies.siteReviewApproval(site, a, reasonCode = "looks fine"),
                MerchantApprovalBodies.with(fields, "decision", str("APPROVED")),
                MerchantApprovalBodies.with(fields, "operation", str("ApprovePaymentMethodActivation")),
            ).forEach { body -> withClue(body) { review(body).shouldBeNull() } }
        }
    }

    Given("minor-4: the deadline-confirmation parser accepts each body with exactly one parser") {
        Then("B3 bodies are not accepted by the B2 parsers and B2 bodies are not accepted by the B3 parsers") {
            val a = Approval()
            val b3 = listOf(ActivationBodies.activationApproval(act, site, a), ActivationBodies.siteReviewApproval(site, a))
            b3.forEach { body ->
                withClue(body) {
                    MerchantApprovalCommandParser.contractRevisionApproval(body.toByteArray(), window).shouldBeNull()
                    MerchantApprovalCommandParser.firstDefaultIssuance(body.toByteArray(), window).shouldBeNull()
                }
            }
            val b2 = MerchantApprovalBodies.contractRevisionApproval("ctr_01JCCT00000000000000000CT1", a = a)
            approve(b2).shouldBeNull()
            review(b2).shouldBeNull()
            deadline(b2).shouldNotBeNull().operation shouldBe AdminOperation.APPROVE_CONTRACT_REVISION
            // B3 본문에서 operation만 B2 값으로 바꾸면 어느 해석기도 받지 않는다.
            deadline(MerchantApprovalBodies.with(ActivationBodies.activationApprovalFields(act, site, a), "operation", str("ApproveContractRevision"))).shouldBeNull()
            // review n-3: B3 본문의 operation을 다른 B3 operation으로 바꾸면 기한 확인 해석기도 받지 않는다(어느 B3 해석기도 다른 operation을 수용하지 않는다).
            listOf(
                MerchantApprovalBodies.with(ActivationBodies.activationApprovalFields(act, site, a), "operation", str("DecideSiteReview")),
                MerchantApprovalBodies.with(ActivationBodies.activationApprovalFields(act, site, a), "operation", str("ResumePaymentMethodActivation")),
                MerchantApprovalBodies.with(ActivationBodies.siteReviewApprovalFields(site, a), "operation", str("ApprovePaymentMethodActivation")),
                MerchantApprovalBodies.with(ActivationBodies.siteReviewApprovalFields(site, a), "operation", str("EnableCurrency")),
            ).forEach { body -> withClue(body) { deadline(body).shouldBeNull() } }
        }
    }

    Given("1-person bodies (B1 common fields)") {
        Then("payment-method-activations parses (merchantAccountId, paymentMethod CARD); an unknown method or an approval-style key is refused") {
            val s = Submission()
            val command = MerchantOperationsRequestParser.paymentMethodActivation(ActivationBodies.registerActivation(mid, s).toByteArray()).shouldNotBeNull()
            command.merchantAccountId.value shouldBe mid
            command.paymentMethod shouldBe PaymentMethod.CARD
            command.request.actor.value shouldBe s.actor
            listOf(
                ActivationBodies.registerActivation(mid, method = "BANK"),
                ActivationBodies.registerActivation("mid_bad"),
                ActivationBodies.registerActivation(mid).replace("\"actor\":\"admin:", "\"actor\":\"system:"),
            ).forEach { body -> withClue(body) { MerchantOperationsRequestParser.paymentMethodActivation(body.toByteArray()).shouldBeNull() } }
        }

        Then("site-review-rejections parses with REJECTED implied; evidenceRef is optional; a decision field, an approval reason or free text is refused") {
            val ok = MerchantOperationsRequestParser.siteReviewRejection(ActivationBodies.rejectSiteReview(site).toByteArray()).shouldNotBeNull()
            ok.merchantSiteId.value shouldBe site
            ok.reason.value shouldBe ActivationBodies.REJECT_REASON
            ok.evidenceRef.shouldBeNull()
            MerchantOperationsRequestParser.siteReviewRejection(ActivationBodies.rejectSiteReview(site, evidenceRef = "PG-REVIEW/2026-9").toByteArray())
                .shouldNotBeNull().evidenceRef.shouldNotBeNull().value shouldBe "PG-REVIEW/2026-9"
            listOf("DOCUMENT_INSUFFICIENT", "PROHIBITED_BUSINESS", "SITE_UNREACHABLE", "OTHER_POLICY").forEach { code ->
                MerchantOperationsRequestParser.siteReviewRejection(ActivationBodies.rejectSiteReview(site, reasonCode = code).toByteArray()).shouldNotBeNull()
            }
            listOf(
                ActivationBodies.rejectSiteReview(site).dropLast(1) + ",\"decision\":\"APPROVED\"}",
                ActivationBodies.rejectSiteReview(site).dropLast(1) + ",\"decision\":\"REJECTED\"}",
                ActivationBodies.rejectSiteReview(site, reasonCode = "REVIEW_PASSED"),
                ActivationBodies.rejectSiteReview(site, reasonCode = "bad site"),
                ActivationBodies.rejectSiteReview(site, evidenceRef = "has space"),
            ).forEach { body -> withClue(body) { MerchantOperationsRequestParser.siteReviewRejection(body.toByteArray()).shouldBeNull() } }
        }
    }
})
