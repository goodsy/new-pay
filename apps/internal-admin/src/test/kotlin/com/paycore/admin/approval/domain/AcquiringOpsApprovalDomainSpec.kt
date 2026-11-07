package com.paycore.admin.approval.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * 재매입 승인 종류의 Domain 규칙(ACQ-ADMIN-001 B1-A — D-ACQA-002 요청 OPERATOR·승인 APPROVER, D-ACQA-003 원 파일 하나의 레코드 1..50건, §17.5 2). 명시 Fixture: 원 파일
 * `ACQ.SIMACQA.TEST.20261105.001`의 SALE 1·3이 기관 거절(세대 0), 원승인 10,000원.
 */
class AcquiringOpsApprovalDomainSpec : BehaviorSpec({

    val fileId = "ACQ.SIMACQA.TEST.20261105.001"
    val now = Instant.parse("2026-11-07T09:00:00.000Z")
    val approvalId = "apr_01JC0000000000000000000A01"
    fun item(seq: Int, status: String = "REJECTED", net: Long = 10_000) =
        PinnedReacquisitionItem(seq, "ptx_ex$seq", "SALE", 0, status, 10_000, 10_000 - net, net)
    val pinned = PinnedReacquisitionTarget(fileId, listOf(item(1), item(3, net = 5_000)))

    Given("AD-1 the new kind (owner-service, OPERATOR → APPROVER, target = acquisition file)") {
        Then("it executes in two transactions, cannot activate a privileged role and is requested by OPERATOR") {
            val kind = ApprovalKind.ACQ_REACQUISITION
            listOf(kind.executionModel, kind.requesterRole, kind.approverRole, kind.targetKind, kind.mayActivatePrivilegedRole(null), kind.escalatesPrivilege) shouldBe
                listOf(ApprovalExecutionModel.OWNER_SERVICE, "OPERATOR", "APPROVER", ApprovalTargetKind.ACQUISITION_FILE, false, false)
        }
        Then("the target is the original file id (FINANCIAL file contract v1 grammar); the version column is fixed 0 — the authoritative version is the pinned record list") {
            val target = ApprovalTarget.AcquisitionFile(fileId)
            listOf(target.kind, target.ref, target.version) shouldBe listOf(ApprovalTargetKind.ACQUISITION_FILE, fileId, 0L)
            shouldThrow<IllegalArgumentException> { ApprovalTarget.AcquisitionFile("ACQ.SIMACQA.TEST.2026115.001") }
            shouldThrow<IllegalArgumentException> { ApprovalTarget.AcquisitionFile("pay_01JC0000000000000000000A01") }
        }
        Then("two-person rule for a file target: requester ≠ approver (the file is not a person)") {
            ApprovalParticipants.distinct(ApprovalTarget.AcquisitionFile(fileId), "adm_a", "adm_b") shouldBe true
            ApprovalParticipants.distinct(ApprovalTarget.AcquisitionFile(fileId), "adm_a", "adm_a") shouldBe false
        }
        Then("an approval request on a file: PENDING → EXECUTING → EXECUTED (file reference) or EXECUTED with code NOT_REQUIRED (D-ACQA-004) or OWNER_REJECTED") {
            val request = ApprovalRequest.create(
                approvalId, ApprovalKind.ACQ_REACQUISITION, ApprovalTarget.AcquisitionFile(fileId), pinned.toSnapshotJson(), null, null, "adm_req", "Req", now,
                Duration.ofHours(24),
            )
            val executing = request.beginExecution("adm_apv", "Apv", now.plusSeconds(1))
            listOf(executing.status, executing.executed().status, executing.executedWith("NOT_REQUIRED").resultCode, executing.ownerRejected("TARGET_CHANGED").status) shouldBe
                listOf(ApprovalStatus.EXECUTING, ApprovalStatus.EXECUTED, "NOT_REQUIRED", ApprovalStatus.OWNER_REJECTED)
            shouldThrow<IllegalArgumentException> { request.beginExecution("adm_req", "Req", now.plusSeconds(1)) }
            shouldThrow<IllegalArgumentException> {
                ApprovalRequest.create(approvalId, ApprovalKind.ACQ_REACQUISITION, ApprovalTarget.Payment("pay_01JC0000000000000000000A01", 0), "{}", null, null, "a", "A", now, Duration.ofHours(1))
            }
            shouldThrow<IllegalStateException> { request.executedWith("NOT_REQUIRED") }
        }
    }

    Given("AD-2 the owner execution row for a re-acquisition (acd1 content hash, FINANCIAL result file id)") {
        val start = OwnerExecution.start(approvalId, "{}".toByteArray(), "acd1:" + "a".repeat(64), now, now.plusSeconds(60), "adm_apv", now.plusSeconds(33))
        Then("the fingerprint may be oct1 or acd1; the owner reference may be a cancellation id or a re-acquisition file id; NOT_REQUIRED is a code") {
            start.ownerResult(now, null, "ACQ.SIMACQA.TEST.20261105.002").ownerReference shouldBe "ACQ.SIMACQA.TEST.20261105.002"
            start.ownerResult(now, "NOT_REQUIRED", null).ownerResultCode shouldBe "NOT_REQUIRED"
            shouldThrow<IllegalArgumentException> { OwnerExecution.start(approvalId, "{}".toByteArray(), "acd2:" + "a".repeat(64), now, now.plusSeconds(60), "x", now) }
            shouldThrow<IllegalArgumentException> { start.ownerResult(now, null, "ACQ.SIMACQA.TEST.20261105") }
        }
        Then("B1-A review m-4: the fingerprint prefix and the owner reference grammar must match the approval kind (acd1 + file id ↔ re-acquisition, oct1 + pcn_ ↔ operator cancel)") {
            val acq = ApprovalKind.ACQ_REACQUISITION
            val cancel = ApprovalKind.OPERATOR_CORRECTION_CANCEL
            fun startAs(kind: ApprovalKind, prefix: String) =
                OwnerExecution.start(kind, approvalId, "{}".toByteArray(), "$prefix:" + "a".repeat(64), now, now.plusSeconds(60), "adm_apv", now.plusSeconds(33))
            listOf(startAs(acq, "acd1").conformsTo(acq), startAs(cancel, "oct1").conformsTo(cancel), start.conformsTo(cancel)) shouldBe listOf(true, true, false)
            shouldThrow<IllegalArgumentException> { startAs(acq, "oct1") }
            shouldThrow<IllegalArgumentException> { startAs(cancel, "acd1") }
            listOf(
                OwnerExecution.referenceFits(acq, "ACQ.SIMACQA.TEST.20261105.002"), OwnerExecution.referenceFits(acq, "pcn_01JC0000000000000000000001"),
                OwnerExecution.referenceFits(cancel, "pcn_01JC0000000000000000000001"), OwnerExecution.referenceFits(cancel, "ACQ.SIMACQA.TEST.20261105.002"),
            ) shouldBe listOf(true, false, true, false)
            // 이미 기록된 참조도 종류와 대조한다(재구성 행 — 회복 job 진입 전 검사).
            start.ownerResult(now, null, "pcn_01JC0000000000000000000001").conformsTo(acq) shouldBe false
        }
    }

    Given("AD-3 the pinned target (authoritative values compared in the approval TX1) and the request snapshot") {
        // B1-A review n-1: 운영에서 쓰지 않는 parse(역변환)를 지웠다 — 저장소는 JSON 열을 필드로 엄격 해석한다(JpaAcquiringOpsCommandEntity). 결정적 모양 단언은 그대로.
        Then("the JSON is deterministic; items are 1..50, ascending, unique; the snapshot keeps reason text out of toString") {
            pinned.toSnapshotJson() shouldBe
                """{"fileId":"$fileId","items":[{"seq":1,"transactionId":"ptx_ex1","recordType":"SALE","generation":0,"status":"REJECTED","approvedAmountMinor":10000,""" +
                """"cancelledAmountMinor":0,"expectedNetMinor":10000},{"seq":3,"transactionId":"ptx_ex3","recordType":"SALE","generation":0,"status":"REJECTED",""" +
                """"approvedAmountMinor":10000,"cancelledAmountMinor":5000,"expectedNetMinor":5000}]}"""
            shouldThrow<IllegalArgumentException> { PinnedReacquisitionTarget(fileId, listOf(item(3), item(1))) }
            shouldThrow<IllegalArgumentException> { PinnedReacquisitionTarget(fileId, emptyList()) }
            shouldThrow<IllegalArgumentException> { PinnedReacquisitionTarget(fileId, (1..51).map { item(it) }) }
            val snapshot = ReacquisitionRequestSnapshot(approvalId, fileId, "INSTITUTION_REQUESTED", "INQ-2026-0001", "secret reason text", pinned, now)
            snapshot.toString().contains("secret") shouldBe false
            shouldThrow<IllegalArgumentException> { ReacquisitionRequestSnapshot(approvalId, "ACQ.OTHER.TEST.20261105.001", "INSTITUTION_REQUESTED", "INQ-1", "r", pinned, now) }
        }
        Then("B1-A review M-1: the snapshot refuses an evidence reference that looks like a card number (13..19 digits, one space/hyphen between digits allowed) — never stored") {
            listOf("4111111111111111", "4111-1111-1111-1111", "INQ-4111111111111111").forEach { evidence ->
                shouldThrow<IllegalArgumentException> { ReacquisitionRequestSnapshot(approvalId, fileId, "INSTITUTION_REQUESTED", evidence, "r", pinned, now) }
            }
            ReacquisitionRequestSnapshot(approvalId, fileId, "INSTITUTION_REQUESTED", "INQ-2026-1105-0001", "r", pinned, now).evidenceRef shouldBe "INQ-2026-1105-0001"
        }
        Then("input rules (C-9 fixed reason codes, FINANCIAL evidence grammar, reason text rules, 1..50 ascending positive seqs) — first failing field and a fixed code") {
            fun problem(seqs: List<Int> = listOf(1, 3), code: String = "INSTITUTION_REQUESTED", evidence: String = "INQ-2026-0001", reason: String = "re-send rejected") =
                ReacquisitionInputRules.problem(fileId, seqs, code, evidence, reason)
            listOf(
                problem(), ReacquisitionInputRules.problem("ACQ.bad", listOf(1), "INSTITUTION_REQUESTED", "e", "r"), problem(seqs = emptyList()), problem(seqs = listOf(3, 1)),
                problem(seqs = listOf(0)), problem(seqs = (1..51).toList()), problem(code = "WHIM"), problem(evidence = "has space"), problem(reason = " lead"),
                problem(reason = "card 4111 1111 1111 1111"),
                // B1-A review M-1: 형식에 맞는 근거라도 카드 번호 패턴이면 거부(운영자 보정 취소 referenceProblem 선례). 날짜·일련번호를 구분 기호로 끊은 참조는 통과.
                problem(evidence = "4111111111111111"), problem(evidence = "4111-1111-1111-1111"), problem(evidence = "INQ-2026-1105-0001"),
            ) shouldBe listOf(
                null, "fileId" to "FORMAT", "seqs" to "COUNT", "seqs" to "ORDER", "seqs" to "ORDER", "seqs" to "COUNT", "reasonCode" to "UNKNOWN_CODE",
                "evidenceRef" to "FORMAT", "reason" to "EDGE_WHITESPACE", "reason" to "CARD_NUMBER_PATTERN",
                "evidenceRef" to "CARD_NUMBER_PATTERN", "evidenceRef" to "CARD_NUMBER_PATTERN", null,
            )
            ReacquisitionInputRules.REASON_CODES shouldBe listOf("INSTITUTION_CONFIRMED_ERROR", "DATA_CORRECTED_AT_SOURCE", "INSTITUTION_REQUESTED")
        }
    }
})
