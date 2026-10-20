package com.paycore.simulator.van.domain

import java.time.Instant
import java.time.LocalDate

/** 취소 거절 분류(고정 Demo 가상 규격 — CANCEL-001 §6.2). */
enum class VanCancelDeclineCategory {
    ORIGINAL_NOT_FOUND,
    ORIGINAL_NOT_APPROVED,
    ORIGINAL_MISMATCH,
    CANCEL_PERIOD_EXPIRED,
    PARTIAL_NOT_SUPPORTED,
    EXCEEDS_REMAINING,
    ISSUER_DECLINED,
}

/**
 * 기관 취소 승인 조건(CANCEL-001 §6.2 CANCEL_RESPONSE APPROVED). [cancelApprovalNumber]만 없을 수 있다(기관이 주지 않을 수 있음 — 메시지에서는 필드 생략, n-5).
 * 거래일·업무일은 Simulator 시계의 Asia/Seoul 날짜(Demo — 업무일 의미는 미결 Q-CALENDAR).
 */
data class VanCancelTerms(
    val cancelApprovalNumber: String?,
    val providerTransactionId: String,
    val originalProviderTransactionId: String,
    val cancelledAmountMinor: Long,
    val currency: String,
    val institutionTransactionDate: LocalDate,
    val institutionBusinessDate: LocalDate,
) {
    init {
        require(cancelledAmountMinor >= 1) { "cancelled amount must be positive" }
    }
}

/** 한 원 승인에 이미 승인된 취소의 수 n과 금액 합 Σ — 한 SELECT(한 스냅샷)로 읽는다(rev 0.3 m-6). */
data class VanApprovedCancels(val count: Int, val sumMinor: Long) {
    init {
        require(count >= 0 && sumMinor >= 0 && (count == 0) == (sumMinor == 0L)) { "approved cancels are a count and a sum of positive amounts" }
    }

    companion object {
        val NONE = VanApprovedCancels(0, 0)
    }
}

/**
 * 가상 VAN 취소 원장 행(append-only — 기관 금융 사실 원본, CANCEL-001 §6.5 `van_cancel_entry`). namespace(operation CANCEL)당 한 행: 결정(DECISION — 승인/거절)이거나
 * 무효과 종결 표지(CLOSURE). 승인 결정은 같은 원 승인 안의 순번 [approvedSequence](n + 1)를 가진다 — 순번 UNIQUE가 같은 원 승인의 동시 승인 취소를 직렬화한다(CN-22).
 * [cardRef]는 원 승인의 카드 참조(시나리오 — CN-25)다. 확정 뒤 바뀌지 않는다.
 */
class VanCancelEntry private constructor(
    val entryId: String,
    val namespace: VanNamespace,
    val kind: VanEntryKind,
    val payloadHash: String?,
    val originalRequestId: String?,
    val cardRef: String?,
    val outcome: VanDecisionOutcome?,
    val terms: VanCancelTerms?,
    val approvedSequence: Int?,
    val declineCategory: VanCancelDeclineCategory?,
    val createdAt: Instant,
) {
    init {
        require(namespace.operation == VanNamespace.CANCEL) { "a cancel entry lives in the CANCEL namespace" }
        when (kind) {
            VanEntryKind.DECISION -> {
                require(payloadHash != null && originalRequestId != null && outcome != null) { "a cancel decision has its payload hash, original and outcome" }
                val approved = outcome == VanDecisionOutcome.APPROVED
                require(approved == (terms != null) && approved == (approvedSequence != null)) { "cancel terms and sequence exist exactly for APPROVED" }
                require((outcome == VanDecisionOutcome.DECLINED) == (declineCategory != null)) { "a decline category exists exactly for DECLINED" }
                require(approvedSequence == null || approvedSequence >= 1) { "an approved sequence starts at 1" }
            }
            VanEntryKind.CLOSURE -> require(
                payloadHash == null && originalRequestId == null && cardRef == null && outcome == null && terms == null && approvedSequence == null && declineCategory == null,
            ) { "a closure tombstone carries no decision" }
        }
    }

    override fun equals(other: Any?): Boolean = other is VanCancelEntry && entryId == other.entryId && namespace == other.namespace && kind == other.kind &&
        payloadHash == other.payloadHash && originalRequestId == other.originalRequestId && outcome == other.outcome && terms == other.terms &&
        approvedSequence == other.approvedSequence && declineCategory == other.declineCategory

    override fun hashCode(): Int = entryId.hashCode()

    override fun toString(): String = "VanCancelEntry(entryId=$entryId, kind=$kind, outcome=$outcome, approvedSequence=$approvedSequence)"

    companion object {
        @Suppress("LongParameterList")
        fun approved(
            namespace: VanNamespace, decisionId: String, payloadHash: String, originalRequestId: String, cardRef: String?, terms: VanCancelTerms, sequence: Int, at: Instant,
        ) = VanCancelEntry(decisionId, namespace, VanEntryKind.DECISION, payloadHash, originalRequestId, cardRef, VanDecisionOutcome.APPROVED, terms, sequence, null, at)

        @Suppress("LongParameterList")
        fun declined(
            namespace: VanNamespace, decisionId: String, payloadHash: String, originalRequestId: String, cardRef: String?, category: VanCancelDeclineCategory, at: Instant,
        ) = VanCancelEntry(decisionId, namespace, VanEntryKind.DECISION, payloadHash, originalRequestId, cardRef, VanDecisionOutcome.DECLINED, null, null, category, at)

        /** 무효과 종결 표지(받은 적 없는 취소 요청 ID — 원 승인·카드 참조도 모른다). */
        fun closure(namespace: VanNamespace, closureId: String, at: Instant) =
            VanCancelEntry(closureId, namespace, VanEntryKind.CLOSURE, null, null, null, null, null, null, null, at)

        /** 영속 복원(Mapper 전용). */
        @Suppress("LongParameterList")
        fun reconstitute(
            entryId: String, namespace: VanNamespace, kind: VanEntryKind, payloadHash: String?, originalRequestId: String?, cardRef: String?, outcome: VanDecisionOutcome?,
            terms: VanCancelTerms?, approvedSequence: Int?, declineCategory: VanCancelDeclineCategory?, createdAt: Instant,
        ) = VanCancelEntry(entryId, namespace, kind, payloadHash, originalRequestId, cardRef, outcome, terms, approvedSequence, declineCategory, createdAt)
    }
}
