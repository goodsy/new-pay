package com.paycore.simulator.van.domain

import java.time.Instant
import java.time.LocalDate

enum class VanEntryKind { DECISION, CLOSURE }

enum class VanDecisionOutcome { APPROVED, DECLINED }

/** 거절 분류(고정 — AUTH-001 §5.2). */
enum class VanDeclineCategory { ISSUER_DECLINED, AUTHENTICATION_INVALID, PLAN_NOT_SUPPORTED }

/**
 * 기관 승인 조건. 거래일·영업일은 Simulator 시계의 Asia/Seoul 날짜다(Demo — 영업일의 달력 의미는 PayCore 미결 Q-CALENDAR이며 여기서 규칙을 만들지 않는다).
 */
data class VanApproval(
    val approvalNumber: String,
    val providerTransactionId: String,
    val amountMinor: Long,
    val currency: String,
    val plan: VanPlan,
    val cardType: String,
    val institutionTransactionDate: LocalDate,
    val institutionBusinessDate: LocalDate,
)

/**
 * 가상 VAN 원장 행(append-only — 기관 금융 사실 원본, AUTH-001 §5.5). namespace당 한 행: 결정(DECISION — 승인/거절)이거나 무효과 종결 표지(CLOSURE). 결정 행은
 * 요청 payload hash·증거 참조·카드 참조(시나리오)를 함께 남겨 같은 요청 ID 재전송·조회를 판정한다. 확정 뒤 바뀌지 않는다(PENDING의 결정 확정도 새 행이다).
 */
class VanLedgerEntry private constructor(
    val entryId: String,
    val namespace: VanNamespace,
    val kind: VanEntryKind,
    val payloadHash: String?,
    val evidenceRef: String?,
    val cardRef: String?,
    val outcome: VanDecisionOutcome?,
    val approval: VanApproval?,
    val declineCategory: VanDeclineCategory?,
    val createdAt: Instant,
) {
    init {
        when (kind) {
            VanEntryKind.DECISION -> {
                require(payloadHash != null && outcome != null) { "a decision has its payload hash and outcome" }
                require((outcome == VanDecisionOutcome.APPROVED) == (approval != null)) { "approval terms exist exactly for APPROVED" }
                require((outcome == VanDecisionOutcome.DECLINED) == (declineCategory != null)) { "a decline category exists exactly for DECLINED" }
            }
            VanEntryKind.CLOSURE -> require(payloadHash == null && evidenceRef == null && outcome == null && approval == null && declineCategory == null) {
                "a closure tombstone carries no decision"
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is VanLedgerEntry && entryId == other.entryId && namespace == other.namespace && kind == other.kind &&
        payloadHash == other.payloadHash && outcome == other.outcome && approval == other.approval && declineCategory == other.declineCategory

    override fun hashCode(): Int = entryId.hashCode()

    override fun toString(): String = "VanLedgerEntry(entryId=$entryId, kind=$kind, outcome=$outcome)"

    companion object {
        fun approved(namespace: VanNamespace, decisionId: String, payloadHash: String, evidenceRef: String, cardRef: String?, approval: VanApproval, at: Instant) =
            VanLedgerEntry(decisionId, namespace, VanEntryKind.DECISION, payloadHash, evidenceRef, cardRef, VanDecisionOutcome.APPROVED, approval, null, at)

        fun declined(
            namespace: VanNamespace, decisionId: String, payloadHash: String, evidenceRef: String, cardRef: String?, category: VanDeclineCategory, at: Instant,
        ) = VanLedgerEntry(decisionId, namespace, VanEntryKind.DECISION, payloadHash, evidenceRef, cardRef, VanDecisionOutcome.DECLINED, null, category, at)

        /** 무효과 종결 표지(받은 적 없는 요청 — 카드 참조도 없다). */
        fun closure(namespace: VanNamespace, closureId: String, cardRef: String?, at: Instant) =
            VanLedgerEntry(closureId, namespace, VanEntryKind.CLOSURE, null, null, cardRef, null, null, null, at)

        /** 영속 복원(Mapper 전용). */
        @Suppress("LongParameterList")
        fun reconstitute(
            entryId: String, namespace: VanNamespace, kind: VanEntryKind, payloadHash: String?, evidenceRef: String?, cardRef: String?, outcome: VanDecisionOutcome?,
            approval: VanApproval?, declineCategory: VanDeclineCategory?, createdAt: Instant,
        ) = VanLedgerEntry(entryId, namespace, kind, payloadHash, evidenceRef, cardRef, outcome, approval, declineCategory, createdAt)
    }
}
