package com.paycore.financial.acquiring.domain

import com.paycore.financial.acquiring.domain.AcquisitionFixture.included
import com.paycore.financial.acquiring.domain.AcquisitionFixture.sale
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * 재매입 2인 승인 기록 검사(RQ-1..RQ-6 — 2-G, D-ACQ-011·D-ADMIN-003/007). 대상 상태(REJECTED·UNKNOWN·파일 거절), 요청자 ≠ 승인자, 최신 generation, 대상 버전 일치,
 * 서명 채널, generation + 1·원 레코드 참조를 순수 domain으로 고정한다. 실행(새 버전 봉인)은 규칙의 범위가 아니다.
 */
class ReacquisitionRuleSpec : BehaviorSpec({

    val fileId = AcquisitionFileId.parse("ACQ.SIMACQA.TEST.20261105.001")
    val record = included(sale("ptx_rq", 10_000)).toRecord(2)
    val now = Instant.parse("2026-11-07T09:00:00Z")
    fun command(
        status: ReacquisitionTargetStatus = ReacquisitionTargetStatus.REJECTED,
        generation: Int = 0,
        requestedBy: String = "admin-req-1",
        approvedBy: String = "admin-apv-2",
        channel: ApprovalChannel = ApprovalChannel.ADMIN_SIGNED,
    ) = ReacquisitionApprovalCommand(
        approvalId = "apr-0001", fileId = fileId, seq = 2, expectedTargetGeneration = generation, expectedTargetStatus = status, requestedBy = requestedBy,
        approvedBy = approvedBy, reasonCode = "ACQUIRER_REQUESTED", evidenceRef = "RECON-CASE-0001", channel = channel,
    )
    fun target(status: RecordStatus, latest: Int = 0, accepted: Boolean = false) = ReacquisitionTarget(fileId, record, status, latest, accepted)

    Given("RQ-1 a two-person approval for a REJECTED record (D-ACQ-011)") {
        val d = ReacquisitionRule.decide(command(), target(RecordStatus.REJECTED), now).shouldBeInstanceOf<ReacquisitionDecision.Approved>()
        Then("the approval is the next generation of the target and refers to it as {fileId}#{seq}") {
            with(d.approval) {
                listOf(targetGeneration, newGeneration, reacquisitionOf, transactionId, recordType, targetStatus, recordedAt) shouldBe
                    listOf(0, 1, "ACQ.SIMACQA.TEST.20261105.001#2", "ptx_rq", AcquisitionRecordType.SALE, ReacquisitionTargetStatus.REJECTED, now)
            }
        }
    }

    Given("RQ-2 whole-file rejected records are re-acquirable (D-ACQ-011 incl. file rejection — I-7); UNKNOWN needs a reconciliation confirmation (D-ACQ-014)") {
        Then("FILE_REJECTED is approved; UNKNOWN is refused UNKNOWN_NOT_RECONCILED until reconciliation can confirm non-acquisition") {
            ReacquisitionRule.decide(command(ReacquisitionTargetStatus.FILE_REJECTED), target(RecordStatus.FILE_REJECTED), now)
                .shouldBeInstanceOf<ReacquisitionDecision.Approved>()
            ReacquisitionRule.decide(command(ReacquisitionTargetStatus.UNKNOWN), target(RecordStatus.UNKNOWN), now) shouldBe
                ReacquisitionDecision.Refused(ReacquisitionRefusal.UNKNOWN_NOT_RECONCILED)
        }
    }

    Given("RQ-3 the requester approves their own request (D-ADMIN-003)") {
        Then("it is refused SAME_PERSON and the approval value object cannot even be built") {
            ReacquisitionRule.decide(command(requestedBy = "admin-x", approvedBy = "admin-x"), target(RecordStatus.REJECTED), now) shouldBe
                ReacquisitionDecision.Refused(ReacquisitionRefusal.SAME_PERSON)
            val ok = (ReacquisitionRule.decide(command(), target(RecordStatus.REJECTED), now) as ReacquisitionDecision.Approved).approval
            shouldThrow<AcquisitionContractViolation> { ok.copy(approvedBy = ok.requestedBy) }
            shouldThrow<AcquisitionContractViolation> { ok.copy(newGeneration = 2) }
            shouldThrow<AcquisitionContractViolation> { ok.copy(reacquisitionOf = "ACQ.SIMACQA.TEST.20261105.001#1") }
        }
    }

    Given("RQ-4 targets that are not re-acquirable") {
        Then("ACCEPTED (acquired), AWAITING (no result yet) and CONFLICT (quarantined) are refused") {
            listOf(RecordStatus.ACCEPTED, RecordStatus.AWAITING, RecordStatus.CONFLICT).forEach {
                ReacquisitionRule.decide(command(), target(it), now) shouldBe ReacquisitionDecision.Refused(ReacquisitionRefusal.TARGET_NOT_REACQUIRABLE)
            }
        }
    }

    Given("RQ-5 the target changed after the request (D-ADMIN-003 '요청 후 대상 변경 시 무효') or a newer generation exists") {
        Then("other expected status or generation → TARGET_CHANGED; a generation 1 already approved → NOT_LATEST_GENERATION") {
            ReacquisitionRule.decide(command(ReacquisitionTargetStatus.UNKNOWN), target(RecordStatus.REJECTED), now) shouldBe
                ReacquisitionDecision.Refused(ReacquisitionRefusal.TARGET_CHANGED)
            ReacquisitionRule.decide(command(generation = 1), target(RecordStatus.REJECTED), now) shouldBe ReacquisitionDecision.Refused(ReacquisitionRefusal.TARGET_CHANGED)
            ReacquisitionRule.decide(command(), target(RecordStatus.REJECTED, latest = 1), now) shouldBe ReacquisitionDecision.Refused(ReacquisitionRefusal.NOT_LATEST_GENERATION)
        }
    }

    Given("RQ-7 some generation of the transaction and type is already ACCEPTED (D-ACQ-014)") {
        Then("the approval is refused ALREADY_ACCEPTED even if this record itself is REJECTED") {
            ReacquisitionRule.decide(command(), target(RecordStatus.REJECTED, accepted = true), now) shouldBe ReacquisitionDecision.Refused(ReacquisitionRefusal.ALREADY_ACCEPTED)
        }
    }

    Given("RQ-8 re-checking a recorded approval before execution (D-ACQ-014 — a late ACCEPTED of the earlier generation invalidates it)") {
        val approval = (ReacquisitionRule.decide(command(), target(RecordStatus.REJECTED), now) as ReacquisitionDecision.Approved).approval
        Then("unchanged target (latest generation = the approval's new generation) stays approved; a late ACCEPTED, another status or another generation is TARGET_CHANGED") {
            ReacquisitionRule.revalidate(approval, target(RecordStatus.REJECTED, latest = 1)) shouldBe ReacquisitionDecision.Approved(approval)
            listOf(
                target(RecordStatus.REJECTED, latest = 1, accepted = true),
                target(RecordStatus.CONFLICT, latest = 1),
                target(RecordStatus.REJECTED, latest = 2),
            ).forEach { ReacquisitionRule.revalidate(approval, it) shouldBe ReacquisitionDecision.Refused(ReacquisitionRefusal.TARGET_CHANGED) }
        }
    }

    Given("RQ-9 the re-acquisition amount (D-ACQ-013 — approved minus every cancellation received by execution, each once)") {
        Then("it is the net amount; zero is possible; cancellations above the approval are a contract violation") {
            ReacquisitionRule.netAmountMinor(10_000, listOf(3_000, 2_000)) shouldBe 5_000L
            ReacquisitionRule.netAmountMinor(10_000, listOf(10_000)) shouldBe 0L
            ReacquisitionRule.netAmountMinor(10_000, emptyList()) shouldBe 10_000L
            shouldThrow<AcquisitionContractViolation> { ReacquisitionRule.netAmountMinor(10_000, listOf(6_000, 5_000)) }
            shouldThrow<AcquisitionContractViolation> { ReacquisitionRule.netAmountMinor(10_000, listOf(0)) }
        }
    }

    Given("RQ-6 a command that did not come through the ADMIN signed channel, or without evidence") {
        Then("it is refused CHANNEL_NOT_SIGNED; an empty or malformed evidence reference cannot be built") {
            ReacquisitionRule.decide(command(channel = ApprovalChannel.UNSIGNED), target(RecordStatus.REJECTED), now) shouldBe
                ReacquisitionDecision.Refused(ReacquisitionRefusal.CHANNEL_NOT_SIGNED)
            shouldThrow<AcquisitionContractViolation> { command().copy(evidenceRef = "") }
            shouldThrow<AcquisitionContractViolation> { command().copy(evidenceRef = "case 1") }
        }
    }
})
