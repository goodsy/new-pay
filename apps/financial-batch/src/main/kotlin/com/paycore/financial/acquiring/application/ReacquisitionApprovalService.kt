package com.paycore.financial.acquiring.application

import com.paycore.financial.acquiring.application.provided.ExecuteReacquisitionUseCase
import com.paycore.financial.acquiring.application.provided.ReacquisitionApprovalResult
import com.paycore.financial.acquiring.application.provided.ReacquisitionExecutionResult
import com.paycore.financial.acquiring.application.provided.RecordReacquisitionApprovalUseCase
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.AcquisitionRecordResultStore
import com.paycore.financial.acquiring.application.required.ReacquisitionApprovalStore
import com.paycore.financial.acquiring.application.required.ReacquisitionApprovalVerifier
import com.paycore.financial.acquiring.domain.ReacquisitionApprovalCommand
import com.paycore.financial.acquiring.domain.ReacquisitionDecision
import com.paycore.financial.acquiring.domain.ReacquisitionRule
import com.paycore.financial.acquiring.domain.ReacquisitionTarget
import com.paycore.financial.acquiring.domain.RecordResultRule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.temporal.ChronoUnit

/**
 * 재매입 2인 승인 기록·검사(2-G — D-ACQ-011)와 실행 차단. 기록 순서: ADMIN 서명 채널 검증(TX 밖 — 실패면 아무것도 쓰지 않음) → TX(대상 파일 행 FOR UPDATE →
 * 대상 레코드·현재 상태·최신 generation → [ReacquisitionRule] → append-only 승인 기록). **실행은 언제나 거절한다** — 입력 경로(ACQ-ADMIN-001) 전 재매입 실행 불가.
 * 이번 작업에서 운영 검증 구현은 언제나 false이고, runtime 계정은 승인 표 INSERT 권한도 없다(V13) — 세 겹의 차단이다.
 */
@Service
class ReacquisitionApprovalService(
    transactionManager: PlatformTransactionManager,
    private val files: AcquisitionFileRepository,
    private val results: AcquisitionRecordResultStore,
    private val approvals: ReacquisitionApprovalStore,
    private val verifier: ReacquisitionApprovalVerifier,
    private val clock: AcquiringClock,
) : RecordReacquisitionApprovalUseCase, ExecuteReacquisitionUseCase {

    private val transactions = AcquiringLocalTransactions(transactionManager, 1)
    private val log = LoggerFactory.getLogger(ReacquisitionApprovalService::class.java)

    override fun record(command: ReacquisitionApprovalCommand, signature: String): ReacquisitionApprovalResult {
        if (!verifier.verify(command, signature)) {
            log.warn("re-acquisition approval refused approvalId={} — the ADMIN signed channel did not verify it (ACQ-ADMIN-001)", command.approvalId)
            return ReacquisitionApprovalResult(false, CHANNEL_NOT_VERIFIED)
        }
        return transactions.execute({ false }) {
            approvals.find(command.approvalId)?.let { existing ->
                // 리뷰 n-5: 같은 승인 ID 재전송은 대상·사람·대상 버전·사유·근거가 모두 같아야 같은 명령이다.
                val same = existing.fileId == command.fileId && existing.seq == command.seq && existing.requestedBy == command.requestedBy &&
                    existing.approvedBy == command.approvedBy && existing.targetGeneration == command.expectedTargetGeneration &&
                    existing.targetStatus == command.expectedTargetStatus && existing.reasonCode == command.reasonCode && existing.evidenceRef == command.evidenceRef
                return@execute ReacquisitionApprovalResult(same, if (same) null else APPROVAL_ID_CONFLICT)
            }
            files.lockFile(command.fileId) ?: return@execute ReacquisitionApprovalResult(false, TARGET_NOT_FOUND)
            val record = files.records(command.fileId).singleOrNull { it.seq == command.seq } ?: return@execute ReacquisitionApprovalResult(false, TARGET_NOT_FOUND)
            val status = RecordResultRule.status(results.entries(command.fileId)[record.seq].orEmpty())
            val latest = maxOf(record.generation, approvals.latestGeneration(record.transactionId, record.type) ?: record.generation)
            // D-ACQ-014: 거래·종류의 어느 세대든 ACCEPTED면 재매입 대상이 아니다.
            val accepted = results.acceptedRecordOf(record.transactionId, record.type) != null
            val target = ReacquisitionTarget(command.fileId, record, status, latest, accepted)
            when (val d = ReacquisitionRule.decide(command, target, clock.now().truncatedTo(ChronoUnit.MILLIS))) {
                is ReacquisitionDecision.Approved -> {
                    approvals.append(d.approval)
                    log.info("re-acquisition approval recorded approvalId={} fileId={} seq={} newGeneration={}", command.approvalId, command.fileId, record.seq, d.approval.newGeneration)
                    ReacquisitionApprovalResult(true, null)
                }
                is ReacquisitionDecision.Refused -> {
                    log.warn("re-acquisition approval refused approvalId={} reason={}", command.approvalId, d.reason)
                    ReacquisitionApprovalResult(false, d.reason.name)
                }
            }
        }
    }

    override fun execute(approvalId: String): ReacquisitionExecutionResult {
        // D-ACQ-011: 입력 경로(ACQ-ADMIN-001) 전 재매입 실행 불가. 아무것도 읽거나 쓰지 않는다.
        log.warn("re-acquisition execution refused — the input path (ACQ-ADMIN-001) is not available (D-ACQ-011)")
        return ReacquisitionExecutionResult(false, INPUT_PATH_NOT_AVAILABLE)
    }

    private companion object {
        const val CHANNEL_NOT_VERIFIED = "CHANNEL_NOT_VERIFIED"
        const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
        const val APPROVAL_ID_CONFLICT = "APPROVAL_ID_CONFLICT"
        const val INPUT_PATH_NOT_AVAILABLE = "INPUT_PATH_NOT_AVAILABLE"
    }
}
