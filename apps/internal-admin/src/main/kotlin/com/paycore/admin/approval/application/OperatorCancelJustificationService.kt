package com.paycore.admin.approval.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.approval.application.provided.JustificationResult
import com.paycore.admin.approval.application.provided.OperatorCancelJustificationQuery
import com.paycore.admin.approval.application.required.AcquiringOpsCommandStore
import com.paycore.admin.approval.application.required.MerchantOpsCommandStore
import com.paycore.admin.approval.application.required.ApprovalEnvironment
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.OperatorCancelCommandStore
import com.paycore.admin.approval.application.required.OwnerOpsMetrics
import com.paycore.admin.approval.domain.ApprovalKind
import com.paycore.admin.approval.domain.ApprovalParticipants
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * [OperatorCancelJustificationQuery](D-OPS-010, I-126): 짧은 TX 하나에서 승인 행(종류 확인)·명령 행을 비잠금으로 읽고, 조회자 계정 현재 행(세션 조회 Port)으로 허용을 판정한 뒤
 * 감사 1행(`APPROVAL_JUSTIFICATION_VIEWED`, 결과 OK·FORBIDDEN, target_ref = paymentId, detail 없음)을 같은 TX에 쓴다. 감사 쓰기가 실패하면 rollback하고 문구를 돌려주지 않는다
 * (`AUDIT_UNAVAILABLE`). 없는 승인·다른 종류는 404(감사 없음 — 조회할 문구가 없다).
 * ACQ-ADMIN-001 rev 0.6(D-ACQA-005): 재매입 승인(`ACQ_REACQUISITION`)의 근거 참조도 **같은 endpoint·같은 판정(APPROVER 또는 요청자 본인, ACTIVE)·같은 감사 action·같은 속도 제한 창**으로
 * 돌려준다([JustificationResult.EvidenceFound] — 사유 문구 없음, 감사 target_ref = 원 파일 ID). 일반 상세·목록에는 근거 참조가 없다.
 */
@Service
class OperatorCancelJustificationService(
    transactionManager: PlatformTransactionManager,
    private val sessions: AdminSessionQuery,
    private val repository: ApprovalRequestRepository,
    private val commands: OperatorCancelCommandStore,
    private val recorder: AdminAuditRecorder,
    private val environment: ApprovalEnvironment,
    private val opsMetrics: OwnerOpsMetrics,
    private val acquiringCommands: AcquiringOpsCommandStore,
    private val merchantCommands: MerchantOpsCommandStore,
) : OperatorCancelJustificationQuery {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)
    private val windows = ConcurrentHashMap<String, LongArray>()

    override fun view(viewerActorId: String, approvalId: String, context: AdminRequestContext): JustificationResult {
        // I-131(S8 리뷰 L-3): 조회자별 분 단위 고정 창(I-93 방식) — 어떤 조회(없는 ID·거부 포함)든 세고, 넘으면 읽지도 감사하지도 않는다.
        if (!admit(viewerActorId, environment.now().toEpochMilli() / MINUTE_MS)) {
            opsMetrics.justificationRateLimited()
            return JustificationResult.RateLimited
        }
        if (!APPROVAL_ID.matches(approvalId)) return JustificationResult.NotFound
        val viewer = sessions.current(viewerActorId)
        val allowedRole = viewer != null && viewer.isActive && ApprovalParticipants.APPROVER_ROLE in viewer.roles
        return try {
            transactions.execute({ false }) {
                val request = repository.find(approvalId) ?: return@execute JustificationResult.NotFound
                // 종류별 문구(같은 판정·감사): 운영자 보정 취소 = 근거 참조 + 사유 문구(D-OPS-010), 재매입 = 근거 참조만(D-ACQA-005 — 사유 문구는 계속 ADMIN 밖으로 내지 않는다, I-4).
                val (targetRef, found) = when (request.kind) {
                    ApprovalKind.OPERATOR_CORRECTION_CANCEL -> commands.find(approvalId)?.let { c ->
                        c.paymentId to JustificationResult.Found(approvalId, c.purposeReference, c.reason)
                    }
                    ApprovalKind.ACQ_REACQUISITION -> acquiringCommands.find(approvalId)?.let { c ->
                        c.originalFileId to JustificationResult.EvidenceFound(approvalId, c.evidenceRef)
                    }
                    // MERCHANT-OPS-002 B2-2(D-MOPS-008 ①): 가맹점 승인도 근거 참조만, 같은 판정(APPROVER·요청자)·같은 감사(target_ref = merchantId).
                    ApprovalKind.MERCHANT_CONTRACT_APPROVAL, ApprovalKind.MERCHANT_FIRST_MID_ISSUANCE, ApprovalKind.MERCHANT_PAYMENT_METHOD_ACTIVATION,
                    ApprovalKind.MERCHANT_SITE_REVIEW_APPROVAL, ApprovalKind.MERCHANT_CURRENCY_ENABLEMENT, ApprovalKind.MERCHANT_CODE_CHANGE,
                    -> merchantCommands.find(approvalId)?.let { c ->
                        c.merchantId to JustificationResult.EvidenceFound(approvalId, c.evidenceRef)
                    }
                    else -> null
                } ?: return@execute JustificationResult.NotFound
                val allowed = allowedRole || (viewer != null && viewer.isActive && request.requestedBy == viewerActorId)
                recorder.record(
                    AuditRecord(
                        occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS), actorKind = "ADMIN", actorId = viewerActorId, actorDisplayName = viewer?.displayName,
                        action = ACTION, resultCode = if (allowed) "OK" else "FORBIDDEN", correlationId = context.correlationId, approvalId = approvalId,
                        remoteIp = context.remoteIp, targetRef = targetRef,
                    ),
                )
                if (allowed) found else JustificationResult.Forbidden
            }
        } catch (e: RuntimeException) {
            log.warn("operation=approval-justification approvalId={} result=audit-unavailable failure={}", approvalId, e.javaClass.simpleName)
            JustificationResult.AuditUnavailable
        }
    }

    /** 고정 창(분) 계수: [0] = 창 번호, [1] = 그 창의 호출 수(인스턴스 메모리 — 다중 인스턴스 합산 한도는 LIVE Gate, I-93과 같다). */
    private fun admit(actorId: String, minute: Long): Boolean {
        val slot = windows.computeIfAbsent(actorId) { LongArray(2) }
        synchronized(slot) {
            if (slot[0] != minute) {
                slot[0] = minute
                slot[1] = 0
            }
            if (slot[1] >= environment.justificationPerMinute) return false
            slot[1]++
            return true
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        val log = LoggerFactory.getLogger(OperatorCancelJustificationService::class.java)
        const val ACTION = "APPROVAL_JUSTIFICATION_VIEWED"
        val APPROVAL_ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
    }
}
