package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.str
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovalFingerprint
import com.paycore.master.merchant.application.provided.ApprovedMerchantCodeChangeCommand
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.MerchantCodeChangeReason
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * MERCHANT-CODE-001 §8 MC-1b-2(§3.7.2): `merchant-code-changes` 본문 엄격 해석(단위) — 모르는 필드·`actor`·`registeredBy` 400, 코드 형식 §3.1(정규화 없음), 사유 C-12 목록.
 * `mop1` 줄 = `mop1`, `ChangeMerchantCode`, approvalId, requestedBy, approvedBy, approvedAt ms, executeBefore ms, evidenceRef, merchantId, currentCode, newCode, reasonCode.
 * 아래 hex 두 개는 Python `hashlib.sha256`으로 따로 계산한 **독립 벡터**(ADMIN `MerchantCodeChangeCommandBodySpec`이 같은 입력·같은 hex를 쓴다).
 */
class MerchantCodeChangeParserSpec : BehaviorSpec({

    val window = ApprovalEvidenceFixtures.DEMO_WINDOW
    val merchantId = "mer_01JCMC00000000000000000MC1"
    fun parse(body: String) = MerchantApprovalCommandParser.merchantCodeChange(body.toByteArray(Charsets.UTF_8), window)
    fun deadline(body: String) = MerchantApprovalCommandParser.deadlineConfirmation(body.toByteArray(Charsets.UTF_8), window)

    Given("the mop1 independent vectors for ChangeMerchantCode (Python hashlib)") {
        fun vector(fields: List<String>) = ApprovalFingerprint.mop1(
            AdminOperation.CHANGE_MERCHANT_CODE, "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H", "admin:adm_01JCRQ0000000000000000REQ1", "admin:adm_01JCAP0000000000000000APR1",
            Instant.parse("2026-11-10T01:00:00.000Z"), Instant.parse("2026-11-10T02:00:00.000Z"), "MERCHANT-REQ/2026-0001", fields,
        )
        Then("two vectors hash to the fixed hex") {
            vector(listOf(merchantId, "shopabcd12", "shopabcd99", "TYPO_CORRECTION")) shouldBe "mop1:b4c41826428235679caa5ba9fd1b5e83ce639a89f68f7be6d331c37edd2854b5"
            vector(listOf(merchantId, "shopabcd12", "newshop2026", "MERCHANT_REQUEST")) shouldBe "mop1:94ad9053551bfac86df334ad8072b6d9ef6977651a108d32e4c05b247cfd591a"
        }
        Then("a one-character change in any command field gives another fingerprint") {
            val base = vector(listOf(merchantId, "shopabcd12", "shopabcd99", "TYPO_CORRECTION"))
            listOf(
                listOf(merchantId.dropLast(1) + "2", "shopabcd12", "shopabcd99", "TYPO_CORRECTION"),
                listOf(merchantId, "shopabcd13", "shopabcd99", "TYPO_CORRECTION"),
                listOf(merchantId, "shopabcd12", "shopabcd98", "TYPO_CORRECTION"),
                listOf(merchantId, "shopabcd12", "shopabcd99", "TYPO_CORRECTIOM"),
            ).forEach { withClue(it) { vector(it) shouldNotBe base } }
        }
    }

    Given("a merchant-code-changes body") {
        Then("it parses: actor = approver, mop1 key, command fields (merchantId, currentCode, newCode, reason); the deadline parser gives the same command") {
            val a = Approval()
            val body = MerchantCodeChangeBodies.body(merchantId, "shopabcd12", "shopabcd99", a = a)
            val command = parse(body).shouldNotBeNull()
            command.request.actor.value shouldBe a.approvedBy
            command.request.idempotencyKey.value shouldBe "mop1.${a.approvalId}"
            command.commandFields shouldBe listOf(merchantId, "shopabcd12", "shopabcd99", "TYPO_CORRECTION")
            command.reason shouldBe MerchantCodeChangeReason.TYPO_CORRECTION
            deadline(body).shouldBeInstanceOf<ApprovedMerchantCodeChangeCommand>() shouldBe command
        }
        Then("400 (null): unknown field, actor, registeredBy, missing field, uppercase / hyphen / 5-char code, unknown reason, wrong operation, foreign fingerprint, non-string") {
            val f = MerchantCodeChangeBodies.fields(merchantId, "shopabcd12", "shopabcd99")
            listOf(
                MerchantApprovalBodies.with(f, "extra", str("x")),
                MerchantApprovalBodies.with(f, "actor", str("admin:adm_01JCAP0000000000000000APR1")),
                MerchantApprovalBodies.with(f, "registeredBy", str("admin:adm_01JCRG0000000000000000RG01")),
                MerchantApprovalBodies.without(f, "reasonCode"),
                MerchantCodeChangeBodies.body(merchantId, "shopabcd12", "ShopAbcd99"),
                MerchantCodeChangeBodies.body(merchantId, "shopabcd12", "shop-abcd9"),
                MerchantCodeChangeBodies.body(merchantId, "shop1", "shopabcd99"),
                MerchantCodeChangeBodies.body(merchantId, "shopabcd12", "shopabcd99", reasonCode = "CHANGED_MIND"),
                MerchantApprovalBodies.with(f, "operation", str("EnableCurrency")),
                MerchantCodeChangeBodies.body(merchantId, "shopabcd12", "shopabcd99", fingerprint = "mop1:" + "0".repeat(64)),
                MerchantApprovalBodies.with(f, "merchantId", "null"),
                MerchantCodeChangeBodies.body("biz_01JCMC00000000000000000MC1", "shopabcd12", "shopabcd99"),
            ).forEachIndexed { i, body -> withClue(i) { parse(body).shouldBeNull() } }
        }
        Then("a contract approval body is not taken by this parser (exact field sets — one parser per body)") {
            parse(MerchantApprovalBodies.contractRevisionApproval("ctr_01JCMC00000000000000000MC1")).shouldBeNull()
        }
    }
})
