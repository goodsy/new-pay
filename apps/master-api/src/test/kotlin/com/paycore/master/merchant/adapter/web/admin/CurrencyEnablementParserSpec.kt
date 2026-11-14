package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.str
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovalFingerprint
import com.paycore.master.merchant.application.provided.ApprovedCurrencyEnablementCommand
import com.paycore.master.merchant.application.provided.CurrencyEffectiveStart
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * MERCHANT-OPS-002 §8.4 B3-2-2(§8.3 B3-2-1, D-MOPS-009 ② — §9.26, C-41·C-48): `currency-enablements` 본문 엄격 해석(단위).
 * `effectiveFrom` = 정확히 `"AT_EXECUTION"`(대소문자 변형·공백 400) 또는 정규 `…Z` ms 시각이면서 **KST 00:00**(아니면 400), `effectiveTo` = 없음 또는 KST 00:00 시각, `currency` = KRW만.
 * `mop1` 끝 줄 = 본문 문자열 그대로(`AT_EXECUTION` / `…Z`), `effectiveTo` 없으면 빈 줄. 아래 hex는 Python `hashlib.sha256`으로 따로 계산한 **독립 벡터**(ADMIN 시험이 같은 입력·같은 hex를 쓴다 — C-23).
 */
class CurrencyEnablementParserSpec : BehaviorSpec({

    val window = ApprovalEvidenceFixtures.DEMO_WINDOW
    val mid = "mid_01JCMD00000000000000000MD1"
    fun parse(body: String) = MerchantApprovalCommandParser.currencyEnablement(body.toByteArray(Charsets.UTF_8), window)
    fun deadline(body: String) = MerchantApprovalCommandParser.deadlineConfirmation(body.toByteArray(Charsets.UTF_8), window)

    Given("the mop1 independent vectors for EnableCurrency (Python hashlib — one per effective-start mode)") {
        fun vector(fields: List<String>) = ApprovalFingerprint.mop1(
            AdminOperation.ENABLE_CURRENCY, "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H", "admin:adm_01JCRQ0000000000000000REQ1", "admin:adm_01JCAP0000000000000000APR1",
            Instant.parse("2026-11-10T01:00:00.000Z"), Instant.parse("2026-11-10T02:00:00.000Z"), "VAN-REPLY/2026-0001", fields,
        )
        Then("AT_EXECUTION (literal mode string, empty effectiveTo line) and DATE (the KST-midnight …Z string) hash to the fixed hex") {
            vector(listOf(mid, "KRW", "AT_EXECUTION", "")) shouldBe "mop1:5ea49cdd62867862a979164a25bb92b1d975c6196da901615e3a281c2f6fc69e"
            vector(listOf(mid, "KRW", "2026-11-13T15:00:00.000Z", "")) shouldBe "mop1:4af9088d55a7a7ba776d809f6379ede4a1e3a145297f8d96dfc396bb27e03222"
            vector(listOf(mid, "KRW", "2026-11-13T15:00:00.000Z", "2026-12-31T15:00:00.000Z")) shouldBe
                "mop1:c20d15571857d2f145c2de47c7f8d631a58f85dde22e5b85613bd48c894ce7b6"
        }

        Then("a one-character change in any command field gives another fingerprint") {
            val base = vector(listOf(mid, "KRW", "AT_EXECUTION", ""))
            listOf(
                listOf(mid.dropLast(1) + "2", "KRW", "AT_EXECUTION", ""),
                listOf(mid, "KRX", "AT_EXECUTION", ""),
                listOf(mid, "KRW", "AT_EXECUTIOM", ""),
                listOf(mid, "KRW", "AT_EXECUTION", "2"),
                listOf(mid, "KRW", "2026-11-13T15:00:00.001Z", ""),
            ).forEach { fields -> withClue(fields) { vector(fields) shouldNotBe base } }
        }
    }

    Given("a currency-enablements body in the AT_EXECUTION mode") {
        Then("it parses: actor = approver, mop1 key, command fields = (mid, KRW, the literal AT_EXECUTION, empty line); the deadline parser gives the same command") {
            val a = Approval()
            val body = CurrencyBodies.body(mid, a = a)
            val command = parse(body).shouldNotBeNull()
            command.request.actor.value shouldBe a.approvedBy
            command.request.idempotencyKey.value shouldBe "mop1.${a.approvalId}"
            command.merchantAccountId.value shouldBe mid
            command.currency.value shouldBe "KRW"
            command.effectiveFrom shouldBe CurrencyEffectiveStart.AtExecution
            command.effectiveTo.shouldBeNull()
            command.operation shouldBe AdminOperation.ENABLE_CURRENCY
            command.commandFields shouldBe listOf(mid, "KRW", "AT_EXECUTION", "")
            command.evidence.fingerprint shouldBe a.fingerprint(AdminOperation.ENABLE_CURRENCY, listOf(mid, "KRW", "AT_EXECUTION", ""))
            deadline(body).shouldBeInstanceOf<ApprovedCurrencyEnablementCommand>() shouldBe command
        }

        Then("AT_EXECUTION with a KST-midnight effectiveTo parses; the end line is the body string") {
            val command = parse(CurrencyBodies.body(mid, effectiveTo = CurrencyBodies.DAY_AFTER_KST)).shouldNotBeNull()
            command.effectiveTo shouldBe Instant.parse(CurrencyBodies.DAY_AFTER_KST)
            command.commandFields shouldBe listOf(mid, "KRW", "AT_EXECUTION", CurrencyBodies.DAY_AFTER_KST)
        }
    }

    Given("a currency-enablements body in the DATE mode") {
        Then("a KST 00:00 start parses as that instant; the command field is the body string (not a re-rendered instant)") {
            val a = Approval()
            val command = parse(CurrencyBodies.body(mid, effectiveFrom = CurrencyBodies.TOMORROW_KST, a = a)).shouldNotBeNull()
            command.effectiveFrom shouldBe CurrencyEffectiveStart.OnDate(Instant.parse(CurrencyBodies.TOMORROW_KST))
            command.commandFields shouldBe listOf(mid, "KRW", CurrencyBodies.TOMORROW_KST, "")
            parse(CurrencyBodies.body(mid, effectiveFrom = CurrencyBodies.TOMORROW_KST, effectiveTo = CurrencyBodies.DAY_AFTER_KST)).shouldNotBeNull()
                .commandFields shouldBe listOf(mid, "KRW", CurrencyBodies.TOMORROW_KST, CurrencyBodies.DAY_AFTER_KST)
        }
    }

    Given("C-48 / C-41: refused bodies (400 — the parser returns null; no value reaches a message)") {
        Then("effectiveFrom must be exactly AT_EXECUTION or a canonical KST-midnight …Z ms instant") {
            listOf(
                "at_execution", "At_Execution", " AT_EXECUTION", "AT_EXECUTION ", "AT EXECUTION", "", "NOW", "DATE",
                // KST 00:00이 아닌 시각(UTC 자정 = 09:00 KST, 1 ms 어긋남, 1분 어긋남).
                "2026-10-09T00:00:00.000Z", "2026-10-09T15:00:00.001Z", "2026-10-09T15:01:00.000Z", "2026-10-09T14:59:59.999Z",
                // 정규형이 아닌 표기(ms 없음·offset·날짜만).
                "2026-10-09T15:00:00Z", "2026-10-10T00:00:00.000+09:00", "2026-10-10",
            ).forEach { from -> withClue("effectiveFrom=[$from]") { parse(CurrencyBodies.body(mid, effectiveFrom = from)).shouldBeNull() } }
        }

        Then("effectiveTo, when present, must be a canonical KST-midnight instant (no mode string, no empty string, no JSON null)") {
            listOf("AT_EXECUTION", "", "2026-10-10T00:00:00.000Z", "2026-10-10T15:00:00.001Z", "2026-10-10T15:00:00Z").forEach { to ->
                withClue("effectiveTo=[$to]") { parse(CurrencyBodies.body(mid, effectiveTo = to)).shouldBeNull() }
            }
            parse(MerchantApprovalBodies.with(CurrencyBodies.fields(mid), "effectiveTo", "null")).shouldBeNull()
        }

        Then("only KRW (C-41); a bad MID, another or missing operation, an actor field, a missing effectiveFrom or a foreign fingerprint is refused") {
            val a = Approval()
            val fields = CurrencyBodies.fields(mid, a = a)
            listOf(
                CurrencyBodies.body(mid, a = a, currency = "USD"),
                CurrencyBodies.body(mid, a = a, currency = "krw"),
                CurrencyBodies.body(mid, a = a, currency = "EUR"),
                CurrencyBodies.body("mid_bad", a = a),
                MerchantApprovalBodies.without(fields, "operation"),
                MerchantApprovalBodies.with(fields, "operation", str("ApprovePaymentMethodActivation")),
                MerchantApprovalBodies.with(fields, "actor", str(a.approvedBy)),
                MerchantApprovalBodies.without(fields, "effectiveFrom"),
                MerchantApprovalBodies.with(fields, "effectiveFrom", "null"),
                // 지문은 본문 문자열로 계산해야 한다 — 확정 시각·`null` 줄로 계산한 지문은 400.
                CurrencyBodies.body(mid, a = a, fingerprint = a.fingerprint(AdminOperation.ENABLE_CURRENCY, listOf(mid, "KRW", "2026-10-09T02:00:00.000Z", ""))),
                CurrencyBodies.body(mid, a = a, fingerprint = a.fingerprint(AdminOperation.ENABLE_CURRENCY, listOf(mid, "KRW", "AT_EXECUTION", "null"))),
                CurrencyBodies.body(mid, a = a, fingerprint = a.fingerprint(AdminOperation.ENABLE_CURRENCY, listOf(mid, "KRW", "AT_EXECUTION"))),
            ).forEach { body -> withClue(body) { parse(body).shouldBeNull() } }
        }
    }

    Given("minor-4: the deadline-confirmation parser accepts a currency body with exactly one parser") {
        Then("the currency body is not accepted by the B2/B3-1 parsers, and their bodies are not accepted by the currency parser") {
            val a = Approval()
            val body = CurrencyBodies.body(mid, a = a).toByteArray()
            MerchantApprovalCommandParser.contractRevisionApproval(body, window).shouldBeNull()
            MerchantApprovalCommandParser.firstDefaultIssuance(body, window).shouldBeNull()
            MerchantApprovalCommandParser.paymentMethodActivationApproval(body, window).shouldBeNull()
            MerchantApprovalCommandParser.siteReviewApproval(body, window).shouldBeNull()
            parse(ActivationBodies.activationApproval("act_01JCAC00000000000000000AC1", "site_01JCST00000000000000000ST1", a)).shouldBeNull()
            parse(MerchantApprovalBodies.contractRevisionApproval("ctr_01JCCT00000000000000000CT1", a = a)).shouldBeNull()
            deadline(CurrencyBodies.body(mid, effectiveFrom = CurrencyBodies.TOMORROW_KST, a = a)).shouldNotBeNull().operation shouldBe AdminOperation.ENABLE_CURRENCY
            deadline(MerchantApprovalBodies.with(CurrencyBodies.fields(mid, a = a), "operation", str("DecideSiteReview"))).shouldBeNull()
        }
    }
})
