package com.paycore.financial.acquiring.domain

import com.paycore.financial.acquiring.domain.AcquisitionFixture.included
import com.paycore.financial.acquiring.domain.AcquisitionFixture.sale
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

/**
 * 재매입 실행 판정(EX-1..EX-7 — ACQ-ADMIN-001 B1, D-ACQ-011·013·014, D-ACQA-003·004). 명시 Fixture(Test Fixture Policy): 원 파일 001의 SALE 레코드 1·2·3이 기관 거절,
 * 원승인 10,000원. 취소 금액·판정 수는 업무 조건으로 직접 준다.
 */
class ReacquisitionExecutionRuleSpec : BehaviorSpec({

    val original = AcquisitionFileId.parse("ACQ.SIMACQA.TEST.20261105.001")
    val approvedAt = Instant.parse("2026-11-07T09:00:00.000Z")
    val now = approvedAt.plusSeconds(5)
    val window = Duration.ofHours(24)
    val clockWindow = Duration.ofMinutes(5)
    fun record(seq: Int, amount: Long = 10_000) = included(sale("ptx_ex$seq", amount)).toRecord(seq)

    /** 거절된 레코드 하나(기본: REJECTED, 세대 0, 다른 세대 없음, ACCEPTED 없음, 취소 없음, 보류 없음). */
    fun rejected(
        seq: Int,
        status: RecordStatus = RecordStatus.REJECTED,
        cancellations: List<Long> = emptyList(),
        undecided: Int = 0,
        held: Boolean = false,
        accepted: Boolean = false,
        latest: Int = 0,
        notRequired: Boolean = false,
        fundingAmount: Long = 10_000,
    ) = seq to ReacquisitionItemTarget(
        ReacquisitionTarget(original, record(seq), status, latest, accepted), notRequired,
        ReacquisitionFunding(fundingAmount, "KRW", cancellations, undecided, held),
    )

    fun command(
        vararg seqs: Int = intArrayOf(1),
        requestedBy: String = "adm_req",
        approvedBy: String = "adm_apv",
        status: ReacquisitionTargetStatus = ReacquisitionTargetStatus.REJECTED,
        at: Instant = approvedAt,
        before: Instant = approvedAt.plus(Duration.ofHours(1)),
        reason: String = "INSTITUTION_REQUESTED",
        evidence: String = "INQ-2026-0001",
    ) = ReacquisitionCommand(
        approvalId = "apr_01JEXAMPLE", originalFileId = original, items = seqs.map { ReacquisitionItem(it, 0, status) }, requestedBy = requestedBy,
        approvedBy = approvedBy, reasonCode = reason, evidenceRef = evidence, approvedAt = at, executeBefore = before,
    )

    fun plan(c: ReacquisitionCommand, vararg targets: Pair<Int, ReacquisitionItemTarget>, at: Instant = now) =
        ReacquisitionExecutionRule.plan(c, targets.toMap(), at, window, clockWindow)

    Given("EX-1 an approved bundle of two rejected records, one with two cancellations received since (D-ACQ-013 net)") {
        val d = plan(command(1, 3), rejected(1), rejected(3, cancellations = listOf(3_000, 2_000))).shouldBeInstanceOf<ReacquisitionPlanDecision.Planned>()
        Then("the new file records are numbered 1..n in original order, next generation, refer to the original, and carry the net amount") {
            d.records.map { listOf(it.seq, it.transactionId, it.generation, it.reacquisitionOf, it.approvedAmountMinor, it.nettedCancelAmountMinor, it.recordAmountMinor) } shouldBe
                listOf(
                    listOf(1, "ptx_ex1", 1, "$original#1", 10_000L, 0L, 10_000L),
                    listOf(2, "ptx_ex3", 1, "$original#3", 10_000L, 5_000L, 5_000L),
                )
            d.approvals.map { it.seq to it.newGeneration } shouldBe listOf(1 to 1, 3 to 1)
            d.notRequired.shouldBeEmpty()
        }
    }

    Given("EX-2 one record of the bundle is no longer re-acquirable (D-ACQA-003 — the whole approval is refused, no partial execution)") {
        Then("the refusal names the record and nothing is planned for the others") {
            plan(command(1, 2), rejected(1), rejected(2, accepted = true)) shouldBe ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.ALREADY_ACCEPTED, 2)
            plan(command(1, 2), rejected(1), rejected(2, latest = 1)) shouldBe ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.NOT_LATEST_GENERATION, 2)
            plan(command(1, 2), rejected(1)) shouldBe ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.TARGET_NOT_FOUND, 2)
            plan(command(1), rejected(1, status = RecordStatus.FILE_REJECTED)) shouldBe ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.TARGET_CHANGED, 1)
            plan(command(1, status = ReacquisitionTargetStatus.UNKNOWN), rejected(1, status = RecordStatus.UNKNOWN)) shouldBe
                ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.UNKNOWN_NOT_RECONCILED, 1)
        }
    }

    Given("EX-3 the net amount at execution is 0 (fully cancelled since — D-ACQA-004)") {
        Then("no record is made; it is closed as 're-acquisition not required'; a mixed bundle seals only the positive one") {
            val zero = plan(command(1), rejected(1, cancellations = listOf(10_000))).shouldBeInstanceOf<ReacquisitionPlanDecision.Planned>()
            zero.records.shouldBeEmpty()
            zero.notRequired.map { listOf(it.approval.seq, it.approvedAmountMinor, it.cancelledAmountMinor) } shouldBe listOf(listOf(1, 10_000L, 10_000L))
            val mixed = plan(command(1, 2), rejected(1, cancellations = listOf(4_000, 6_000)), rejected(2)).shouldBeInstanceOf<ReacquisitionPlanDecision.Planned>()
            mixed.records.map { it.seq to it.transactionId } shouldBe listOf(1 to "ptx_ex2")
            mixed.notRequired.map { it.approval.transactionId } shouldBe listOf("ptx_ex1")
            mixed.approvals.map { it.seq } shouldBe listOf(1, 2)
        }
    }

    Given("EX-4 owner-side re-checks of the approval (D-ADMIN-007) and fail-closed inputs") {
        Then("same person, execution window, expiry, CANCEL record, held payment, undecided cancellation, inconsistent facts, closed target are refused") {
            plan(command(1, requestedBy = "adm_x", approvedBy = "adm_x"), rejected(1)) shouldBe ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.SAME_PERSON, null)
            plan(command(1, before = approvedAt.plus(Duration.ofHours(25))), rejected(1)) shouldBe
                ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.APPROVAL_WINDOW_INVALID, null)
            plan(command(1), rejected(1), at = approvedAt.minus(Duration.ofMinutes(6))) shouldBe
                ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.APPROVAL_WINDOW_INVALID, null)
            plan(command(1), rejected(1), at = approvedAt.plus(Duration.ofHours(1))) shouldBe
                ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.APPROVAL_EXECUTION_EXPIRED, null)
            plan(command(1), rejected(1, held = true)) shouldBe ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.PAYMENT_HELD, 1)
            plan(command(1), rejected(1, cancellations = listOf(1_000), undecided = 1)) shouldBe
                ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.CANCELLATION_UNDECIDED, 1)
            plan(command(1), rejected(1, fundingAmount = 9_000)) shouldBe ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.TARGET_INCONSISTENT, 1)
            plan(command(1), rejected(1, cancellations = listOf(10_001))) shouldBe ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.TARGET_INCONSISTENT, 1)
            plan(command(1), rejected(1, notRequired = true)) shouldBe ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.ALREADY_NOT_REQUIRED, 1)
            val cancel = CancelCandidateRule.decide(AcquisitionFixture.cancelAfterMembership(), OriginalSaleResult.ACCEPTED, AcquisitionFixture.NEXT_CUTOFF)
            val cancelRecord = rejected(1).let { (s, t) -> s to t.copy(target = t.target.copy(record = (cancel as CancelDecision.Include).toRecord(1))) }
            plan(command(1), cancelRecord) shouldBe ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.CANCEL_NOT_SUPPORTED, 1)
        }
    }

    Given("EX-5 the content fingerprint of a command (replay detection — same approval id, same content)") {
        val base = command(1, 3)
        Then("it is stable for the same content and changes with any business field") {
            base.contentHash() shouldMatch Regex("^acd1:[0-9a-f]{64}$")
            // 독립 계산 벡터(Python hashlib — 명세 §4 내용 hash 계약: 길이 접두 canonical, correlationId 제외). ADMIN 쪽 계산도 같은 벡터로 대조한다(B1 ADMIN 절반).
            base.contentHash() shouldBe "acd1:8ea4f6b6eafbe81ffe250745743dfee1dff033e6986abc4547239efc95e4cf7f"
            command(1, 3).contentHash() shouldBe base.contentHash()
            listOf(
                command(1), command(1, 3, requestedBy = "adm_o"), command(1, 3, approvedBy = "adm_o"), command(1, 3, reason = "DATA_CORRECTED_AT_SOURCE"),
                command(1, 3, evidence = "INQ-2026-0002"), command(1, 3, before = approvedAt.plus(Duration.ofHours(2))),
                command(1, 3, status = ReacquisitionTargetStatus.FILE_REJECTED), base.copy(approvalId = "apr_01JOTHER"),
            ).forEach { it.contentHash() shouldNotBe base.contentHash() }
        }
    }

    Given("EX-6 malformed commands") {
        Then("no items, more than 50, unordered or duplicated seqs, a deadline before the approval are contract violations") {
            shouldThrow<AcquisitionContractViolation> { command(*IntArray(0)) }
            shouldThrow<AcquisitionContractViolation> { command(*(1..51).toList().toIntArray()) }
            shouldThrow<AcquisitionContractViolation> { command(3, 1) }
            shouldThrow<AcquisitionContractViolation> { command(1, 1) }
            shouldThrow<AcquisitionContractViolation> { command(1, before = approvedAt) }
        }
    }

    Given("EX-7 the re-acquisition file is the next version of the same day and contains only re-acquisition records") {
        val d = plan(command(1, 3), rejected(1), rejected(3)) as ReacquisitionPlanDecision.Planned
        val header = AcquisitionFileHeader(AcquisitionFileId.parse("ACQ.SIMACQA.TEST.20261105.002"), "PAYCORE", now.plusMillis(0), "acq-cutoff-demo-1", original)
        val (file, rendered) = AcquisitionFile.sealReacquisition(header, AcquisitionFixture.CUTOFF, d.records)
        Then("it seals with the original reference and round-trips; a first-acquisition record or a regular version is refused") {
            file.header.originalFileId shouldBe original
            rendered.totals.recordCount shouldBe 2
            AcquisitionFileFormat.parse(rendered.content).records shouldBe d.records
            shouldThrow<AcquisitionContractViolation> { AcquisitionFile.sealReacquisition(header, AcquisitionFixture.CUTOFF, listOf(record(1))) }
            shouldThrow<AcquisitionContractViolation> { AcquisitionFile.sealReacquisition(header, AcquisitionFixture.CUTOFF, emptyList()) }
            shouldThrow<AcquisitionContractViolation> { AcquisitionFile.sealReacquisition(AcquisitionFixture.header(), AcquisitionFixture.CUTOFF, d.records) }
        }
        Then("n-3 (B1-F review, B2): every record re-acquires a record of the header's original file — another file of the lane/day, another lane or a malformed reference is refused") {
            // 헤더 init이 originalFileId = 같은 lane·업무일의 더 낮은 버전을 이미 지킨다(아래 첫 줄). sealReacquisition은 레코드 원 참조가 그 원 파일을 가리키는지를 더 검사한다.
            shouldThrow<AcquisitionContractViolation> {
                AcquisitionFileHeader(AcquisitionFileId.parse("ACQ.SIMACQA.TEST.20261105.002"), "PAYCORE", now, "acq-cutoff-demo-1", AcquisitionFileId.parse("ACQ.SIMACQB.TEST.20261105.001"))
            }
            listOf(
                "ACQ.SIMACQA.TEST.20261104.001#1", "ACQ.SIMACQB.TEST.20261105.001#1", "ACQ.SIMACQA.TEST.20261105.002#1", "${original.value}#0", "${original.value}#x",
                original.value,
            ).forEach { ref ->
                val records = d.records.mapIndexed { i, r -> if (i == 0) r.copy(reacquisitionOf = ref) else r }
                shouldThrow<AcquisitionContractViolation> { AcquisitionFile.sealReacquisition(header, AcquisitionFixture.CUTOFF, records) }
            }
        }
    }
})
