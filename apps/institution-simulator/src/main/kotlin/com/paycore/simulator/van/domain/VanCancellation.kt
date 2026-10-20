package com.paycore.simulator.van.domain

import java.time.Duration
import java.time.Instant

/**
 * 가상 VAN 취소 판정(CANCEL-001 §6.3 — 앞 단계가 결정하면 멈춤, 순수 함수):
 * 1. 같은 취소 namespace 행: 종결 표지 → REJECTED_CLOSED, 결정 → payload hash 같으면 재응답·다르면 REQUEST_CONFLICT(원장 불변).
 * 2. environment ≠ TEST·요청 ID 시각이 취소 조회 보존기간 밖 → 기록 없이 거절. (Demo) 기록 전 끊김 카드 → 기록 없이 끊기.
 * 3. 원 승인(AUTHORIZE namespace 원장): 없음·종결 표지 → ORIGINAL_NOT_FOUND, APPROVED 아님 → ORIGINAL_NOT_APPROVED, 원 PTX·승인번호·통화·거래일 echo 불일치 → ORIGINAL_MISMATCH.
 * 4. (Demo) 취소 기한: 시각 − 원 승인 기록 시각 > `cancel.window` → CANCEL_PERIOD_EXPIRED.
 * 5. 부분 = 금액 < 원 승인액 ∨ 이미 승인된 취소 있음. 프로파일이 (원 Plan, cardType) 부분취소를 지원하지 않으면 PARTIAL_NOT_SUPPORTED. scope echo ≠ 이 판정 → ORIGINAL_MISMATCH.
 * 6. 기관 잔액: 금액 > 원 승인액 − Σ(승인된 취소) → EXCEEDS_REMAINING. [approved]는 n·Σ를 한 스냅샷으로 읽은 값이다(m-6).
 * 7. 가상 카드 시나리오 → 결정. APPROVED는 순번 n + 1(같은 원 승인의 동시 승인은 순번 UNIQUE 1062 → 새 TX 재판정 — CN-22). 거절도 기록한다.
 */
object VanCancellation {

    @Suppress("LongParameterList", "ReturnCount", "CyclomaticComplexMethod")
    fun judge(
        clientId: String,
        request: VanCancelRequest,
        existing: VanCancelEntry?,
        original: VanLedgerEntry?,
        approved: VanApprovedCancels,
        profile: VanProfile,
        ids: VanDecisionIds,
        at: Instant,
    ): VanCancelJudgement {
        val namespace = VanNamespace.of(clientId, request)
        val hash = request.payloadHash()
        if (existing != null) {
            require(existing.namespace == namespace) { "the cancel ledger row belongs to another namespace" }
            return when {
                existing.kind == VanEntryKind.CLOSURE -> VanCancelJudgement.RejectedClosed
                existing.payloadHash == hash -> VanCancelJudgement.Replay(existing)
                else -> VanCancelJudgement.Conflict
            }
        }
        if (request.environment != TEST) return VanCancelJudgement.Refused
        val requestedAt = VanUlid.timeOf(request.requestId)
        if (requestedAt == null || requestedAt.plus(profile.cancel.inquiryRetention).isBefore(at)) return VanCancelJudgement.Refused
        if (original != null) {
            require(original.namespace.operation == VanNamespace.AUTHORIZE && original.namespace.requestId == request.originalRequestId) {
                "the original row is not the authorization the cancel names"
            }
            require(original.namespace.clientId == clientId && original.namespace.environment == request.environment &&
                original.namespace.institutionAccount == request.institutionAccount) { "the original row belongs to another account" }
        }
        val cardRef = original?.cardRef
        val behaviour = VanCancelBehaviour.ofCard(cardRef)
        if (behaviour == VanCancelBehaviour.DROP_BEFORE) return VanCancelJudgement.DropWithoutRecord

        fun decline(category: VanCancelDeclineCategory) = VanCancelJudgement.Decide(
            VanCancelEntry.declined(namespace, ids.decisionId, hash, request.originalRequestId, cardRef, category, at),
            VanCancelBehaviour.NORMAL,
        )

        if (original == null || original.kind == VanEntryKind.CLOSURE) return decline(VanCancelDeclineCategory.ORIGINAL_NOT_FOUND)
        val approval = original.approval
        if (original.outcome != VanDecisionOutcome.APPROVED || approval == null) return decline(VanCancelDeclineCategory.ORIGINAL_NOT_APPROVED)
        val echoes = request.originalProviderTransactionId == approval.providerTransactionId && request.originalApprovalNumber == approval.approvalNumber &&
            request.currency == approval.currency && request.originalTransactionDate == approval.institutionTransactionDate
        if (!echoes) return decline(VanCancelDeclineCategory.ORIGINAL_MISMATCH)
        if (Duration.between(original.createdAt, at) > profile.cancel.window) return decline(VanCancelDeclineCategory.CANCEL_PERIOD_EXPIRED)
        val partial = request.cancelAmountMinor < approval.amountMinor || approved.count > 0
        if (partial && !profile.cancel.supportsPartial(approval.plan, approval.cardType)) return decline(VanCancelDeclineCategory.PARTIAL_NOT_SUPPORTED)
        if (request.scope != (if (partial) VanCancelScope.PARTIAL else VanCancelScope.FULL)) return decline(VanCancelDeclineCategory.ORIGINAL_MISMATCH)
        if (request.cancelAmountMinor > approval.amountMinor - approved.sumMinor) return decline(VanCancelDeclineCategory.EXCEEDS_REMAINING)
        if (behaviour == VanCancelBehaviour.DECLINE) return decline(VanCancelDeclineCategory.ISSUER_DECLINED)

        val cancelled = if (behaviour == VanCancelBehaviour.PARTIAL && request.cancelAmountMinor > 1) request.cancelAmountMinor - 1 else request.cancelAmountMinor
        val terms = VanCancelTerms(
            cancelApprovalNumber = ids.approvalNumber, providerTransactionId = ids.providerTransactionId, originalProviderTransactionId = approval.providerTransactionId,
            cancelledAmountMinor = cancelled, currency = approval.currency, institutionTransactionDate = VanDates.seoulDate(at), institutionBusinessDate = VanDates.seoulDate(at),
        )
        return VanCancelJudgement.Decide(
            VanCancelEntry.approved(namespace, ids.decisionId, hash, request.originalRequestId, cardRef, terms, approved.count + 1, at),
            behaviour,
        )
    }

    private const val TEST = "TEST"
}
