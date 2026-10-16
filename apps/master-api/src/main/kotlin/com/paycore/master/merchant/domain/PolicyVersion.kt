package com.paycore.master.merchant.domain

import java.time.Instant

enum class PolicyKind { FEE_SCHEDULE, SETTLEMENT_POLICY }

/**
 * 정책 내용 상태(D-MS2-003): 이번 등록부에는 계산 내용 모델이 없다. UNDEFINED·DEMO 모두 "내용 없는 버전"이며 TEST/Simulator 전용, LIVE 허용
 * Gate에서 차단 대상이다. 내용 모델은 SETTLEMENT-CONTRACT-001.
 */
enum class PolicyContentStatus { UNDEFINED, DEMO }

/** 수수료·정산 정책 버전 식별 등록부(D-MS2-003, append-only). 같은 (종류, 코드)의 버전 번호는 1부터 단조 증가. */
class PolicyVersion private constructor(
    val id: PolicyVersionId,
    val kind: PolicyKind,
    val code: PolicyCode,
    val versionNo: Int,
    val contentStatus: PolicyContentStatus,
    val contentHash: String?,
    val reason: DecisionReason,
    val registeredAt: Instant,
    val registeredBy: ActorRef,
) {
    init {
        require(versionNo >= 1) { "policy version numbers start at 1" }
        require(contentHash == null || CONTENT_HASH.matches(contentHash)) { "contentHash must be 64 lower-case hex characters" }
    }

    override fun toString(): String = "PolicyVersion(id=${id.value}, kind=$kind, code=${code.value}, versionNo=$versionNo)"

    companion object {
        private val CONTENT_HASH = Regex("^[0-9a-f]{64}$")

        /** 새 버전. [existing]은 같은 (종류, 코드)의 기존 버전 전체. 번호 = 최대 + 1(동시 등록은 UNIQUE가 막고 재시도에서 다음 번호). */
        @Suppress("LongParameterList")
        fun register(
            id: PolicyVersionId,
            kind: PolicyKind,
            code: PolicyCode,
            existing: List<PolicyVersion>,
            contentStatus: PolicyContentStatus,
            contentHash: String?,
            reason: DecisionReason,
            at: Instant,
            by: ActorRef,
        ): PolicyVersion {
            require(existing.all { it.kind == kind && it.code == code }) { "existing versions must be of the same kind and code" }
            if (contentHash != null && !CONTENT_HASH.matches(contentHash)) throw MasterRuleViolation(MasterRejection.POLICY_CONTENT_HASH_INVALID)
            return PolicyVersion(id, kind, code, (existing.maxOfOrNull { it.versionNo } ?: 0) + 1, contentStatus, contentHash, reason, at, by)
        }

        @Suppress("LongParameterList")
        fun reconstitute(
            id: PolicyVersionId,
            kind: PolicyKind,
            code: PolicyCode,
            versionNo: Int,
            contentStatus: PolicyContentStatus,
            contentHash: String?,
            reason: DecisionReason,
            registeredAt: Instant,
            registeredBy: ActorRef,
        ) = PolicyVersion(id, kind, code, versionNo, contentStatus, contentHash, reason, registeredAt, registeredBy)
    }
}
