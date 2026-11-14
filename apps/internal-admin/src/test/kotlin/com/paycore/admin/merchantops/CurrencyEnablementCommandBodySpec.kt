package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.domain.CurrencyEnablementCommandBody
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.time.LocalDate

/**
 * B3-2 통화 활성화 승인 본문·`mop1` 지문(MERCHANT-OPS-002 §9.14·§9.26 — D-MOPS-009 ②, MASTER와 코드 공유 없이 독립 계산). 독립 벡터: Python `hashlib.sha256`으로 따로 계산한 hex
 * (MASTER `CurrencyEnablementParserSpec`와 같은 입력·같은 값 — C-23). 입력: approvalId apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H, requestedBy admin:adm_01JCRQ0000000000000000REQ1,
 * approvedBy admin:adm_01JCAP0000000000000000APR1, approvedAt 2026-11-10T01:00:00.000Z, executeBefore 2026-11-10T02:00:00.000Z, evidenceRef VAN-REPLY/2026-0001, MID mid_01JCMD00000000000000000MD1.
 * 명령 필드 줄 = 본문 문자열 그대로(`AT_EXECUTION` 또는 KST 00:00 `…Z`), `effectiveTo` 없으면 빈 줄이고 본문에는 키가 없다.
 */
class CurrencyEnablementCommandBodySpec : BehaviorSpec({

    val approvedAt = Instant.parse("2026-11-10T01:00:00.000Z")
    val executeBefore = Instant.parse("2026-11-10T02:00:00.000Z")
    val mid = "mid_01JCMD00000000000000000MD1"
    fun body(from: String = "AT_EXECUTION", to: String? = null, currency: String = "KRW", account: String = mid, at: Instant = approvedAt, before: Instant = executeBefore) =
        CurrencyEnablementCommandBody(
            "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H", "admin:adm_01JCRQ0000000000000000REQ1", "admin:adm_01JCAP0000000000000000APR1", at, before, "VAN-REPLY/2026-0001",
            account, currency, from, to,
        )

    Given("CB-1 independent mop1 vectors (Python hashlib — same values as the MASTER tests, one per effective-start mode)") {
        Then("AT_EXECUTION, DATE and DATE + end hash to the fixed hex; one command-field character changes it") {
            body().fingerprint() shouldBe "mop1:5ea49cdd62867862a979164a25bb92b1d975c6196da901615e3a281c2f6fc69e"
            body(from = "2026-11-13T15:00:00.000Z").fingerprint() shouldBe "mop1:4af9088d55a7a7ba776d809f6379ede4a1e3a145297f8d96dfc396bb27e03222"
            body(from = "2026-11-13T15:00:00.000Z", to = "2026-12-31T15:00:00.000Z").fingerprint() shouldBe
                "mop1:c20d15571857d2f145c2de47c7f8d631a58f85dde22e5b85613bd48c894ce7b6"
            body(account = "mid_01JCMD00000000000000000MD2").fingerprint() shouldNotBe body().fingerprint()
        }
    }

    Given("CB-2 the stored body: evidence common 9 + operation EnableCurrency + merchantAccountId, currency, effectiveFrom (+ effectiveTo only when present)") {
        Then("AT_EXECUTION: exact bytes — the literal mode string, no effectiveTo key; the bytes do not depend on the approval time beyond the evidence fields") {
            String(body().json()) shouldBe
                """{"idempotencyKey":"mop1.apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","correlationId":"mop.apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","approvalId":"apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H",""" +
                """"requestedBy":"admin:adm_01JCRQ0000000000000000REQ1","approvedBy":"admin:adm_01JCAP0000000000000000APR1","approvedAt":"2026-11-10T01:00:00.000Z",""" +
                """"executeBefore":"2026-11-10T02:00:00.000Z","evidenceRef":"VAN-REPLY/2026-0001",""" +
                """"approvalFingerprint":"mop1:5ea49cdd62867862a979164a25bb92b1d975c6196da901615e3a281c2f6fc69e","operation":"EnableCurrency",""" +
                """"merchantAccountId":"mid_01JCMD00000000000000000MD1","currency":"KRW","effectiveFrom":"AT_EXECUTION"}"""
            // 승인 시각이 바뀌어도 effectiveFrom은 AT_EXECUTION 문자열 그대로(확정 시각은 MASTER 실행 시각 — §9.26).
            String(body(at = approvedAt.plusSeconds(3600), before = executeBefore.plusSeconds(3600)).json()).contains("\"effectiveFrom\":\"AT_EXECUTION\"") shouldBe true
        }
        Then("DATE with an end: exact command-field tail") {
            String(body(from = "2026-11-13T15:00:00.000Z", to = "2026-12-31T15:00:00.000Z").json()).substringAfter("\"operation\"") shouldBe
                """:"EnableCurrency","merchantAccountId":"mid_01JCMD00000000000000000MD1","currency":"KRW","effectiveFrom":"2026-11-13T15:00:00.000Z",""" +
                """"effectiveTo":"2026-12-31T15:00:00.000Z"}"""
        }
        Then("refused here first (MASTER would 400): another currency (C-41), a non-KST-midnight or non-canonical start/end, a lower-case mode, a bad MID; toString has no evidence") {
            listOf<() -> Any>(
                { body(currency = "USD") }, { body(from = "at_execution") }, { body(from = "2026-11-13T00:00:00.000Z") }, { body(from = "2026-11-13T15:00:00Z") },
                { body(to = "AT_EXECUTION") }, { body(to = "2026-12-31T15:00:00.001Z") }, { body(account = "mer_01JCMD00000000000000000MD1") },
            ).forEachIndexed { i, build -> withClue(i) { shouldThrow<IllegalArgumentException> { build() } } }
            body().toString().contains("VAN-REPLY") shouldBe false
        }
    }

    Given("CB-3 KST date → MASTER instant (C-7 — that day 00:00 Asia/Seoul as a canonical …Z ms string)") {
        Then("2026-11-14 → 2026-11-13T15:00:00.000Z; 2027-01-01 → 2026-12-31T15:00:00.000Z") {
            CurrencyEnablementCommandBody.startOfKstDay(LocalDate.parse("2026-11-14")) shouldBe "2026-11-13T15:00:00.000Z"
            CurrencyEnablementCommandBody.startOfKstDay(LocalDate.parse("2027-01-01")) shouldBe "2026-12-31T15:00:00.000Z"
        }
    }
})
