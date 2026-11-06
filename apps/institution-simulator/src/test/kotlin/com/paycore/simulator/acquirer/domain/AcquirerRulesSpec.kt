package com.paycore.simulator.acquirer.domain

import com.paycore.simulator.acquirer.domain.AcquirerFixture.cancel
import com.paycore.simulator.acquirer.domain.AcquirerFixture.ref
import com.paycore.simulator.acquirer.domain.AcquirerFixture.sale
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * 가상 매입사 규칙(ACQUIRING-001 2-E §7): 수신 멱등·충돌(ReceiptRule), 레코드 판정(RecordJudgementRule — VAN 원장 대조), 게시 계획(PublicationPlanner — 결정적
 * 시나리오), 시나리오 규칙 모양. 모두 순수 Domain 시험(DB·SFTP 없음).
 */
class AcquirerRulesSpec : FunSpec({

    val id = "ACQ.SIMACQA.TEST.20261105.001"
    val at = Instant.parse("2026-11-05T14:06:01.250Z")
    val sha = "a".repeat(64)
    fun receipt(ordinal: Int = 1, contentSha: String = sha, outcome: ReceiptOutcome = ReceiptOutcome.RECEIVED, count: Int? = 0) = AcquirerReceipt(
        "SIMACQA", id, ordinal, contentSha, 10, if (outcome == ReceiptOutcome.RECEIVED) count else null, outcome,
        when (outcome) {
            ReceiptOutcome.RECEIVED -> null
            ReceiptOutcome.FILE_REJECTED -> FileRejectionReason.FORMAT_INVALID
            ReceiptOutcome.RECEIPT_CONFLICT -> FileRejectionReason.CONTENT_MISMATCH
        },
        at,
    )

    fun authorization(r: ReceivedRecord, amount: Long = r.approvedAmountMinor, cancels: Long = r.nettedCancelAmountMinor) = AuthorizationEvidence(
        "ad_01JBV0000000000000000000E1", r.providerTransactionId, r.approvalNumber, amount, "KRW", r.institutionTransactionDate, cancels,
    )

    test("RC-1 same file id: same content is already received (no record), first receipt decides, a different content conflicts with the next ordinal") {
        ReceiptRule.decide(emptyList(), sha) shouldBe ReceiptRule.Decision.First
        ReceiptRule.decide(listOf(receipt()), sha) shouldBe ReceiptRule.Decision.AlreadyReceived
        ReceiptRule.decide(listOf(receipt(), receipt(2, "b".repeat(64), ReceiptOutcome.RECEIPT_CONFLICT)), "b".repeat(64)) shouldBe
            ReceiptRule.Decision.AlreadyReceived
        ReceiptRule.decide(listOf(receipt(), receipt(2, "b".repeat(64), ReceiptOutcome.RECEIPT_CONFLICT)), "c".repeat(64)) shouldBe ReceiptRule.Decision.Conflict(3)
        ReceiptRule.decide((1..99).map { receipt(it, it.toString().padStart(64, '0'), if (it == 1) ReceiptOutcome.RECEIVED else ReceiptOutcome.RECEIPT_CONFLICT) },
            "f".repeat(64)) shouldBe ReceiptRule.Decision.Exhausted
    }

    test("RC-2 receipt invariants: only the first receipt decides, a conflict carries CONTENT_MISMATCH and no record count") {
        shouldThrow<IllegalArgumentException> { receipt(ordinal = 2) }
        shouldThrow<IllegalArgumentException> { receipt(ordinal = 1, outcome = ReceiptOutcome.RECEIPT_CONFLICT) }
        shouldThrow<IllegalArgumentException> {
            AcquirerReceipt("SIMACQA", id, 1, sha, 10, null, ReceiptOutcome.FILE_REJECTED, FileRejectionReason.CONTENT_MISMATCH, at)
        }
    }

    // judgeSale(record, scenario, transactionAccepted, evidence, evidenceAccepted, consumedCancelMinor)
    fun sale(r: ReceivedRecord, e: AuthorizationEvidence?, txnAccepted: Boolean = false, evidenceAccepted: Boolean = false, consumed: Long = 0,
             scenario: AcquirerScenarioRule? = null) = RecordJudgementRule.judgeSale(r, scenario, txnAccepted, e, evidenceAccepted, consumed)

    test("RJ-1 a SALE matching the VAN approval (reference, approval number, date, currency, amount, netted ≤ approved cancels) is ACCEPTED with its evidence") {
        val r = sale(1, "t-a", 20_000, 5_000)
        sale(r, authorization(r)) shouldBe
            RecordJudgement(RecordResult.ACCEPTED, null, "ad_01JBV0000000000000000000E1", "ad_01JBV0000000000000000000E1")
    }

    test("RJ-2 SALE rejections in rule order: scenario, duplicate, no authorization, approval/date, currency, amount, netted cancels") {
        val r = sale(1, "t-a", 20_000, 5_000)
        val rejectScenario = AcquirerScenarioRule(AcquirerScenarioAction.REJECT, transactionId = "t-a", reason = "DEMO_REJECTED")
        mapOf(
            "scenario beats a match" to sale(r, authorization(r), txnAccepted = true, scenario = rejectScenario).reason,
            "already accepted" to sale(r, authorization(r), txnAccepted = true).reason,
            "no authorization" to sale(r, null).reason,
            "approval number" to sale(r, authorization(r).copy(approvalNumber = "30000002")).reason,
            "institution date" to sale(r, authorization(r).copy(institutionTransactionDate = LocalDate.parse("2026-11-04"))).reason,
            "currency" to sale(r, authorization(r).copy(currency = "USD")).reason,
            "approved amount" to sale(r, authorization(r, amount = 20_001)).reason,
            "netted more than VAN cancels" to sale(r, authorization(r, cancels = 4_999)).reason,
        ) shouldBe mapOf(
            "scenario beats a match" to "DEMO_REJECTED", "already accepted" to "DUPLICATE_RECORD", "no authorization" to "NO_MATCHING_AUTHORIZATION",
            "approval number" to "AUTHORIZATION_MISMATCH", "institution date" to "AUTHORIZATION_MISMATCH", "currency" to "CURRENCY_MISMATCH",
            "approved amount" to "AMOUNT_MISMATCH", "netted more than VAN cancels" to "NETTED_CANCEL_MISMATCH",
        )
    }

    val acceptedA = AcceptedSale("t-a", "VAN-T-t-a", "ad_01JBV0000000000000000000E1")

    // judgeCancel(record, scenario, transactionAccepted, originalSale, evidence, evidenceAccepted, consumedCancelMinor, authorizationCancelSumMinor)
    fun cancelOf(c: ReceivedRecord, e: CancellationEvidence?, original: AcceptedSale? = acceptedA, txnAccepted: Boolean = false, evidenceAccepted: Boolean = false,
                 consumed: Long = 0, cancelSum: Long = 5_000) = RecordJudgementRule.judgeCancel(c, null, txnAccepted, original, e, evidenceAccepted, consumed, cancelSum)

    test("RJ-3 a CANCEL is accepted only after its original SALE was accepted here (D-ACQ-009 condition) and against the matching VAN cancellation") {
        val c = cancel(1, "c-a", "t-a", 20_000, 5_000)
        val e = CancellationEvidence("cd_01JBV0000000000000000000E2", "ad_01JBV0000000000000000000E1", c.providerTransactionId,
            checkNotNull(c.originalProviderTransactionId), 5_000, "KRW", c.institutionTransactionDate)
        cancelOf(c, e) shouldBe RecordJudgement(RecordResult.ACCEPTED, null, "cd_01JBV0000000000000000000E2", "ad_01JBV0000000000000000000E1")
        mapOf(
            "original not acquired" to cancelOf(c, e, original = null).reason,
            "no cancellation" to cancelOf(c, null).reason,
            "other original in the VAN cancellation" to cancelOf(c, e.copy(originalProviderTransactionId = "VAN-T-other")).reason,
            "amount" to cancelOf(c, e.copy(cancelledAmountMinor = 4_000)).reason,
            "duplicate transaction" to cancelOf(c, e, txnAccepted = true).reason,
        ) shouldBe mapOf(
            "original not acquired" to "ORIGINAL_NOT_ACQUIRED", "no cancellation" to "NO_MATCHING_CANCELLATION",
            "other original in the VAN cancellation" to "CANCELLATION_MISMATCH", "amount" to "AMOUNT_MISMATCH", "duplicate transaction" to "DUPLICATE_RECORD",
        )
    }

    // 리뷰 MJ-1: 이중 매입 방어는 기관 근거 기준 — 같은 VAN 승인 행의 두 번째 SALE, 같은 VAN 취소 행의 두 번째 CANCEL, 원승인별 취소 소비 누적 한도, 수락된 원 SALE과
    // 다른 원거래 참조. 거래 ID가 달라도 거절한다(기존 사유 코드 재사용).
    test("RJ-4 institution evidence decides double acquisition: same VAN approval/cancellation twice, cumulative cancels over the VAN sum, CANCEL of another original") {
        val again = sale(1, "t-other-txn", 20_000, ptx = "VAN-T-t-a")
        val netted = sale(1, "t-n", 20_000, 3_000)
        val c = cancel(2, "c-b", "t-a", 20_000, 2_000)
        val e = CancellationEvidence("cd_01JBV0000000000000000000E3", "ad_01JBV0000000000000000000E1", c.providerTransactionId,
            checkNotNull(c.originalProviderTransactionId), 2_000, "KRW", c.institutionTransactionDate)
        mapOf(
            "same VAN approval under another transaction id" to sale(again, authorization(again, cancels = 0), evidenceAccepted = true).reason,
            "SALE netting plus accepted CANCELs over the VAN sum" to sale(netted, authorization(netted, cancels = 5_000), consumed = 2_001).reason,
            "same VAN cancellation twice" to cancelOf(c, e, evidenceAccepted = true).reason,
            "netted cancel sent again as a CANCEL" to cancelOf(c, e, consumed = 5_000, cancelSum = 5_000).reason,
            "CANCEL refers to another original than the accepted SALE" to
                cancelOf(cancel(3, "c-x", "t-a", 20_000, 2_000, originalPtx = "VAN-T-other"),
                    e.copy(providerTransactionId = "VAN-C-c-x", originalProviderTransactionId = "VAN-T-other")).reason,
            "cumulative exactly at the VAN sum" to cancelOf(c, e, consumed = 3_000, cancelSum = 5_000).result.name,
            // 재리뷰 n-4: 참조 문자열이 같아도 VAN 취소가 가리키는 원승인 행이 수락 SALE의 근거 행과 다르면 거절.
            "VAN cancellation of another authorization row" to cancelOf(c, e.copy(originalAuthorizationEntryId = "ad_01JBV0000000000000000000E8")).reason,
        ) shouldBe mapOf(
            "same VAN approval under another transaction id" to "DUPLICATE_RECORD",
            "SALE netting plus accepted CANCELs over the VAN sum" to "NETTED_CANCEL_MISMATCH",
            "same VAN cancellation twice" to "DUPLICATE_RECORD",
            "netted cancel sent again as a CANCEL" to "NETTED_CANCEL_MISMATCH",
            "CANCEL refers to another original than the accepted SALE" to "CANCELLATION_MISMATCH",
            "cumulative exactly at the VAN sum" to "ACCEPTED",
            "VAN cancellation of another authorization row" to "CANCELLATION_MISMATCH",
        )
        shouldThrow<IllegalArgumentException> { RecordJudgement(RecordResult.ACCEPTED, null, null) }
    }

    test("PP-1 plan: ACK at receipt + delay, one base result file, delayed/duplicate/conflicting results in later numbered files, withheld nowhere") {
        val records = listOf(sale(1, "t-1", 10_000), sale(2, "sc-withhold", 10_000), sale(3, "sc-delay", 10_000), sale(4, "sc-dup", 10_000), sale(5, "sc-conflict", 10_000))
        val book = AcquirerScenarioBook(
            listOf(
                AcquirerScenarioRule(AcquirerScenarioAction.WITHHOLD_RESULT, transactionId = "sc-withhold"),
                AcquirerScenarioRule(AcquirerScenarioAction.DELAY_RESULT, transactionId = "sc-delay", delay = Duration.ofHours(2)),
                AcquirerScenarioRule(AcquirerScenarioAction.DUPLICATE_RESULT, transactionId = "sc-dup"),
                AcquirerScenarioRule(AcquirerScenarioAction.CONFLICTING_RESULT, transactionId = "sc-conflict"),
                AcquirerScenarioRule(AcquirerScenarioAction.DELAY_ACK, fileId = id, delay = Duration.ofMinutes(5)),
            ),
        )
        val judged = records.map { JudgedRecord(it, RecordJudgement(RecordResult.ACCEPTED, null, "ad_test"), book.forRecord(ref(id), it)) }
        val plan = PublicationPlanner.plan(receipt(count = 5), judged, book, Duration.ofSeconds(1), Duration.ofSeconds(30))
        plan.ackDueAt shouldBe at.plusSeconds(1).plus(Duration.ofMinutes(5))
        plan.resultFiles.map { f -> Triple(f.seq, f.dueAt, f.lines.map { "${it.recordSeq}:${it.result}:${it.reason}" }) } shouldBe listOf(
            Triple(1, at.plusSeconds(30), listOf("1:ACCEPTED:null", "4:ACCEPTED:null", "5:ACCEPTED:null")),
            Triple(2, at.plusSeconds(30), listOf("4:ACCEPTED:null", "5:REJECTED:SCENARIO_CONFLICT")),
            Triple(3, at.plusSeconds(30).plus(Duration.ofHours(2)), listOf("3:ACCEPTED:null")),
        )
    }

    test("PP-2 plan: DROP_ACK leaves no ACK; refused receipts get only an ACK; an empty file gets one empty result file; all withheld → no result file") {
        val dropBook = AcquirerScenarioBook(listOf(AcquirerScenarioRule(AcquirerScenarioAction.DROP_ACK, fileId = id)))
        PublicationPlanner.plan(receipt(), emptyList(), dropBook, Duration.ZERO, Duration.ZERO) shouldBe
            PublicationPlan(null, listOf(PlannedResultFile(1, at, emptyList())))
        PublicationPlanner.plan(receipt(outcome = ReceiptOutcome.FILE_REJECTED), emptyList(), AcquirerScenarioBook.NONE, Duration.ZERO, Duration.ZERO) shouldBe
            PublicationPlan(at, emptyList())
        PublicationPlanner.plan(receipt(2, "b".repeat(64), ReceiptOutcome.RECEIPT_CONFLICT), emptyList(), AcquirerScenarioBook.NONE, Duration.ZERO, Duration.ZERO) shouldBe
            PublicationPlan(at, emptyList())
        val withheld = AcquirerScenarioRule(AcquirerScenarioAction.WITHHOLD_RESULT, approvedAmountMinor = 13_013)
        val r = sale(1, "t-1", 13_013)
        PublicationPlanner.plan(receipt(count = 1), listOf(JudgedRecord(r, RecordJudgement(RecordResult.ACCEPTED, null, "ad_test"), withheld)),
            AcquirerScenarioBook(listOf(withheld)), Duration.ZERO, Duration.ZERO).resultFiles shouldBe emptyList()
    }

    test("SC-1 scenario rules: record rules need exactly one of transaction id or amount, file/SFTP rules need a file id, reasons/delays/times only where meaningful") {
        listOf(
            { AcquirerScenarioRule(AcquirerScenarioAction.REJECT, transactionId = "t", approvedAmountMinor = 1, reason = "X") },
            { AcquirerScenarioRule(AcquirerScenarioAction.REJECT, transactionId = "t") },
            { AcquirerScenarioRule(AcquirerScenarioAction.WITHHOLD_RESULT, transactionId = "t", reason = "X") },
            { AcquirerScenarioRule(AcquirerScenarioAction.DELAY_RESULT, transactionId = "t") },
            { AcquirerScenarioRule(AcquirerScenarioAction.DELAY_RESULT, transactionId = "t", delay = Duration.ofNanos(1)) },
            { AcquirerScenarioRule(AcquirerScenarioAction.DROP_ACK, transactionId = "t") },
            { AcquirerScenarioRule(AcquirerScenarioAction.FILE_REJECT, fileId = "not-a-file-id") },
            { AcquirerScenarioRule(AcquirerScenarioAction.DISCONNECT_DURING_UPLOAD, fileId = id) },
            { AcquirerScenarioRule(AcquirerScenarioAction.DROP_RENAME_RESPONSE, fileId = id, times = 0) },
        ).forEach { shouldThrow<IllegalArgumentException> { it() } }
        AcquirerScenarioBook(listOf(AcquirerScenarioRule(AcquirerScenarioAction.REJECT, approvedAmountMinor = 13_013, reason = "DEMO_REJECTED")))
            .forRecord(ref(id), sale(1, "any", 13_013))?.reason shouldBe "DEMO_REJECTED"
    }
})
