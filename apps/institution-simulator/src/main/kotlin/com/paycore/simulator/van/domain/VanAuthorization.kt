package com.paycore.simulator.van.domain

import java.time.Instant

/**
 * 가상 VAN 승인 판정(AUTH-001 §5.3 — 앞 단계가 결정하면 멈춤, 순수 함수):
 * 1. 같은 namespace 원장 행: 종결 표지 → REJECTED_CLOSED, 결정 → payload hash 같으면 재응답·다르면 REQUEST_CONFLICT. PENDING 예약도 같은 규칙(같으면 처리 중/기한이면 확정).
 * 2. environment ≠ TEST → 기록 없이 거절.
 * 3. (Demo) 기록 전 끊김 카드 → 기록 없이 끊기.
 * 4. 인증 근거 재검증: 증거 있음·같은 client·AUTHENTICATED·바인딩 echo(환경·MID·금액·통화·Plan·기관 코드)·`validUntil` 안·다른 요청 ID 미사용. 아니면 DECLINED
 *    AUTHENTICATION_INVALID — 만료 증거 포함(D-AUTH1-004: PG 게이트는 TTL을 보지 않고 기관이 확정 거절한다).
 * 5. Plan 재검증: 체크카드 할부·프로파일 밖 개월 → DECLINED PLAN_NOT_SUPPORTED(D-AUTH1-003, 범위 §2 "기관에도 재검증").
 * 6. 가상 카드 시나리오 → 결정(또는 PENDING 예약). 결정은 원장 INSERT 뒤 응답한다(application — 거절도 같다, m-7).
 */
object VanAuthorization {

    @Suppress("LongParameterList", "ReturnCount")
    fun judge(
        clientId: String,
        request: VanAuthRequest,
        existing: VanLedgerEntry?,
        pending: VanPendingDecision?,
        evidence: VanEvidence?,
        evidenceUsedByAnotherRequest: Boolean,
        profile: VanProfile,
        ids: VanDecisionIds,
        at: Instant,
    ): VanJudgement {
        val namespace = VanNamespace.of(clientId, request)
        val hash = request.payloadHash()
        if (existing != null) {
            require(existing.namespace == namespace) { "the ledger row belongs to another namespace" }
            return when {
                existing.kind == VanEntryKind.CLOSURE -> VanJudgement.RejectedClosed
                existing.payloadHash == hash -> VanJudgement.Replay(existing)
                else -> VanJudgement.Conflict
            }
        }
        if (pending != null && pending.state == VanPendingState.PENDING) {
            require(pending.namespace == namespace) { "the reservation belongs to another namespace" }
            return when {
                pending.payloadHash != hash -> VanJudgement.Conflict
                pending.isDue(at) -> VanJudgement.ResolvePending(pending, pending.resolve(ids, at))
                else -> VanJudgement.StillPending(pending)
            }
        }
        if (request.environment != TEST) return VanJudgement.Refused
        // 검토 #2 n-3(Demo 규칙): 요청 ID 시각이 조회 보존기간보다 오래된 새 요청은 기록 없이 거절한다 — 조회로도 확인할 수 없는 요청을 승인하지 않는다.
        val requestedAt = VanUlid.timeOf(request.requestId)
        if (requestedAt == null || requestedAt.plus(profile.inquiryRetention).isBefore(at)) return VanJudgement.Refused
        val behaviour = VanBehaviour.ofCard(evidence?.cardRef)
        if (behaviour == VanBehaviour.DROP_BEFORE_RECORD) return VanJudgement.DropWithoutRecord

        if (evidence == null || !evidenceValid(clientId, request, evidence, evidenceUsedByAnotherRequest, at)) {
            return decline(namespace, ids, hash, request, evidence?.cardRef, VanDeclineCategory.AUTHENTICATION_INVALID, at, consumes = false)
        }
        val cardType = checkNotNull(evidence.cardType)
        if (!planSupported(request.plan, cardType, profile)) {
            return decline(namespace, ids, hash, request, evidence.cardRef, VanDeclineCategory.PLAN_NOT_SUPPORTED, at, consumes = true)
        }
        return when (behaviour) {
            VanBehaviour.DECLINE -> decline(namespace, ids, hash, request, evidence.cardRef, VanDeclineCategory.ISSUER_DECLINED, at, consumes = true)
            VanBehaviour.PENDING_THEN_APPROVE ->
                VanJudgement.Hold(VanPendingDecision.hold(ids.pendingId, namespace, request, evidence.cardRef, cardType, at.plus(profile.pendingDelay), at))
            else -> VanJudgement.Decide(
                VanLedgerEntry.approved(namespace, ids.decisionId, hash, request.authentication.evidenceRef, evidence.cardRef, approval(request, cardType, behaviour, ids, at), at),
                behaviour,
                consumesEvidence = true,
            )
        }
    }

    private fun evidenceValid(clientId: String, request: VanAuthRequest, e: VanEvidence, usedByAnotherRequest: Boolean, at: Instant): Boolean {
        val a = request.authentication
        return !usedByAnotherRequest && e.evidenceRef == a.evidenceRef && e.clientId == clientId && e.authenticated && at.isBefore(e.validUntil) &&
            e.environment == request.environment && e.merchantRef == request.merchantRef && e.amountMinor == request.amountMinor && e.currency == request.currency &&
            e.plan == request.plan && e.issuerCode == a.issuerCode && e.acquirerCode == a.acquirerCode && e.providerCode == a.providerCode && e.cardType != null
    }

    private fun planSupported(plan: VanPlan, cardType: String, profile: VanProfile): Boolean = when (plan.kind) {
        VanPlanKind.LUMP_SUM -> true
        VanPlanKind.INSTALLMENT -> cardType == CREDIT && plan.months in profile.creditInstallmentMonths
    }

    private fun approval(request: VanAuthRequest, cardType: String, behaviour: VanBehaviour, ids: VanDecisionIds, at: Instant): VanApproval {
        val amount = if (behaviour == VanBehaviour.PARTIAL_APPROVAL) request.amountMinor - 1 else request.amountMinor
        val type = if (behaviour == VanBehaviour.CARDTYPE_MISMATCH) (if (cardType == CREDIT) CHECK else CREDIT) else cardType
        val plan = if (behaviour == VanBehaviour.INSTALLMENT_MONTHS_MISMATCH && request.plan.kind == VanPlanKind.INSTALLMENT) {
            checkNotNull(request.plan.months).let { m -> VanPlan.installment(if (m * 2 <= MAX_MONTHS) m * 2 else m - 1) }
        } else {
            request.plan
        }
        return VanApproval(ids.approvalNumber, ids.providerTransactionId, amount, request.currency, plan, type, VanDates.seoulDate(at), VanDates.seoulDate(at))
    }

    @Suppress("LongParameterList")
    private fun decline(
        namespace: VanNamespace, ids: VanDecisionIds, hash: String, request: VanAuthRequest, cardRef: String?, category: VanDeclineCategory, at: Instant, consumes: Boolean,
    ) = VanJudgement.Decide(
        VanLedgerEntry.declined(namespace, ids.decisionId, hash, request.authentication.evidenceRef, cardRef, category, at),
        VanBehaviour.NORMAL,
        consumesEvidence = consumes,
    )

    private const val TEST = "TEST"
    private const val MAX_MONTHS = 99
    private const val CREDIT = "CREDIT"
    private const val CHECK = "CHECK"
}
