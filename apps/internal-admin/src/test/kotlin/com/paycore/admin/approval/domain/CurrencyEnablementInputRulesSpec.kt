package com.paycore.admin.approval.domain

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * B3-2 통화 활성화 요청 입력 규칙(MERCHANT-OPS-002 §8.4 B3-2-5 — D-MOPS-009 ②, C-41): `effectiveFromMode` = `AT_EXECUTION`·`DATE`, DATE면 `effectiveFromDate` 필수이며 요청 시각 KST 기준
 * **내일 이후**(advisory — 권위는 MASTER), `effectiveToDate`(C-7 배타 끝) 선택, 통화 KRW만, 근거 참조 필수. 날짜 경계는 명시 값([today] = 2026-11-13 KST).
 */
class CurrencyEnablementInputRulesSpec : BehaviorSpec({

    val today = LocalDate.parse("2026-11-13")
    val m = "mer_01JCMD00000000000000000MR1"
    val mid = "mid_01JCMD00000000000000000MD1"
    @Suppress("LongParameterList")
    fun problem(
        mode: String = "AT_EXECUTION", from: String? = null, to: String? = null, currency: String = "KRW", evidence: String = "VAN-REPLY/2026-0001", account: String = mid,
        merchant: String = m,
    ) = MerchantOpsInputRules.currencyProblem(merchant, account, currency, mode, from, to, evidence, today)

    Given("valid inputs") {
        Then("AT_EXECUTION (no date, optional end tomorrow or later) and DATE tomorrow or later (optional end after the start) pass") {
            listOf(
                problem(), problem(to = "2026-11-14"), problem(mode = "DATE", from = "2026-11-14"), problem(mode = "DATE", from = "2026-11-14", to = "2026-11-15"),
                problem(mode = "DATE", from = "2027-01-01"),
            ) shouldBe List(5) { null }
        }
    }

    Given("refused inputs (400 INVALID_INPUT field + reason — no value in the reason)") {
        Then("each rule names its field") {
            listOf(
                problem(merchant = "mid_x") to ("merchantId" to "FORMAT"),
                problem(account = "act_01JCMD00000000000000000MD1") to ("merchantAccountId" to "FORMAT"),
                problem(currency = "USD") to ("currency" to "NOT_ALLOWED"),
                problem(currency = "krw") to ("currency" to "NOT_ALLOWED"),
                problem(mode = "NOW") to ("effectiveFromMode" to "NOT_ALLOWED"),
                problem(mode = "at_execution") to ("effectiveFromMode" to "NOT_ALLOWED"),
                problem(mode = "AT_EXECUTION", from = "2026-11-14") to ("effectiveFromDate" to "NOT_ALLOWED"),
                problem(mode = "DATE") to ("effectiveFromDate" to "REQUIRED"),
                problem(mode = "DATE", from = "2026/11/14") to ("effectiveFromDate" to "FORMAT"),
                problem(mode = "DATE", from = "2026-02-30") to ("effectiveFromDate" to "FORMAT"),
                // 내일 이후만(오늘·과거는 400 — advisory, 권위는 MASTER CURRENCY_ENABLEMENT_RETROACTIVE).
                problem(mode = "DATE", from = "2026-11-13") to ("effectiveFromDate" to "NOT_FUTURE"),
                problem(mode = "DATE", from = "2026-11-12") to ("effectiveFromDate" to "NOT_FUTURE"),
                problem(to = "2026-11-13") to ("effectiveToDate" to "RANGE"),
                problem(to = "2026-1-14") to ("effectiveToDate" to "FORMAT"),
                problem(mode = "DATE", from = "2026-11-15", to = "2026-11-15") to ("effectiveToDate" to "RANGE"),
                problem(mode = "DATE", from = "2026-11-15", to = "2026-11-14") to ("effectiveToDate" to "RANGE"),
                problem(evidence = "") to ("evidenceRef" to "FORMAT"),
                problem(evidence = "4111111111111111") to ("evidenceRef" to "CARD_NUMBER_PATTERN"),
            ).forEach { (actual, expected) -> withClue(expected) { actual shouldBe expected } }
        }
    }
})
