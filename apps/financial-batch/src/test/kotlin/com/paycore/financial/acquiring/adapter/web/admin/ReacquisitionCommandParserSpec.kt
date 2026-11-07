package com.paycore.financial.acquiring.adapter.web.admin

import com.paycore.financial.acquiring.domain.AcquisitionContractViolation
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.CardNumberPattern
import com.paycore.financial.acquiring.domain.ReacquisitionCommand
import com.paycore.financial.acquiring.domain.ReacquisitionItem
import com.paycore.financial.acquiring.domain.ReacquisitionTargetStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * (ACQ-ADMIN-001 rev 0.7 — B2, I-23 권장안) FINANCIAL 재매입 명령의 근거 참조(evidenceRef) 카드 번호 패턴 거부. ADMIN `OperatorCancelInputRules.containsCardNumber`와
 * **같은 판정**(십진 숫자 `\p{Nd}` 13~19자리, 숫자 사이 공백·하이픈 하나 허용, Luhn 없음)을 FINANCIAL Domain에 두고 ADMIN 시험과 같은 벡터로 고정한다. 근거 문법
 * (`[A-Za-z0-9._:#/-]{1,128}`)이 공백·전각 숫자를 이미 막으므로 FINANCIAL 경로에서 실제로 걸리는 것은 ASCII 숫자·하이픈 연속이다 — 나머지 벡터는 판정 동치 확인용.
 */
class ReacquisitionCommandParserSpec : BehaviorSpec({

    val approvedAt = Instant.parse("2026-11-06T14:59:50.000Z")

    fun body(evidence: String) = (
        """{"approvalId":"apr_parser_1","originalFileId":"ACQ.SIMACQA.TEST.20261105.001","items":[{"seq":1,"expectedGeneration":0,"expectedStatus":"REJECTED"}],""" +
            """"requestedBy":"adm_operator_1","approvedBy":"adm_approver_1","reasonCode":"INSTITUTION_REQUESTED","evidenceRef":"$evidence",""" +
            """"approvedAt":"2026-11-06T14:59:50.000Z","executeBefore":"2026-11-06T15:59:50.000Z","correlationId":"corr-1"}"""
        ).toByteArray()

    fun command(evidence: String) = ReacquisitionCommand(
        approvalId = "apr_parser_1", originalFileId = AcquisitionFileId.parse("ACQ.SIMACQA.TEST.20261105.001"),
        items = listOf(ReacquisitionItem(1, 0, ReacquisitionTargetStatus.REJECTED)), requestedBy = "adm_operator_1", approvedBy = "adm_approver_1",
        reasonCode = "INSTITUTION_REQUESTED", evidenceRef = evidence, approvedAt = approvedAt, executeBefore = approvedAt.plusSeconds(3_600),
    )

    Given("the same card number verdict as ADMIN (OperatorCancelInputRulesSpec vectors — I-17·S5 L-3)") {
        Then("13..19 digit runs with single spaces or hyphens between digits, in any decimal digit script, match; shorter numbers, amounts and 20+ digit runs do not") {
            listOf(
                "card 4111111111111111", "4111 1111 1111 1111", "4111-1111-1111-1111", "1234567890123", "1234567890123456789", "ref 5500-0000-0000-0004 x",
                "card " + "４１１１".repeat(4), "card " + "٤١١١".repeat(4), "card 4111 １１１１ 1111 1111", "RECON-20261028-000001",
            ).map(CardNumberPattern::matches) shouldBe List(10) { true }
            listOf("amount 7000 won", "ticket 123456789012", "tel 010-1234-5678", "12345678901234567890 is too long to be a card", "RECON-2026-0001", "INQ-2026-1105-0001")
                .map(CardNumberPattern::matches) shouldBe List(6) { false }
        }
    }

    Given("an execution command whose evidence reference looks like a card number") {
        Then("the parser refuses it (400 REQUEST_MALFORMED — no echo) so nothing reaches the append-only records") {
            listOf("4111111111111111", "4111-1111-1111-1111", "INQ-1234567890123", "RECON-20261028-000001").forEach { ReacquisitionCommandParser.command(body(it)).shouldBeNull() }
            ReacquisitionCommandParser.command(body("INQ-2026-1105-0001")).shouldNotBeNull().evidenceRef shouldBe "INQ-2026-1105-0001"
        }
        Then("the Domain command refuses it on any other path too (invariant, not only the parser)") {
            shouldThrow<AcquisitionContractViolation> { command("INQ-4111111111111111") }
            command("INQ-2026-0001").evidenceRef shouldBe "INQ-2026-0001"
        }
    }
})
