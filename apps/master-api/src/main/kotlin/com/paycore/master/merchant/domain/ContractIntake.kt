package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 계약 접수 건 Aggregate(MERCHANT-FLOW-001 §4A.3·§4B.6 — MASTER 소유, 사업자 1:N, 영업 진행 기록). 순수 Domain: Spring·JPA·jOOQ에 의존하지 않는다(규칙은 여기, DB 행 모양은
 * `JpaContractIntakeEntity` — §4A.15). 사업자 계약([BusinessContract])과 분리된다(BR-BIZ-313) — 계약 완료 전이가 사업자 계약을 만들거나 새 버전을 더한다(BR-BIZ-314·324).
 *
 * - 식별: (사업자, 생성 순번 [seq]). 순번은 사업자 안 단조 증가(1, 2, …)이고 "최신 접수 건" = 순번이 가장 큰 건이다(리뷰 m-3). 이관 건은 순번 1(§4A.9).
 * - 종류([kind] — BR-BIZ-324·325): 생성 때 잠금 뒤 그 사업자의 최신 사업자 계약이 '계약 있음'(효력 전·유효·정지)이면 [ContractIntakeKind.CHANGE](대상 [targetContractSeq]),
 *   없음·해지·만료이면 [ContractIntakeKind.NEW]. 운영자는 고르지 않는다 — 화면이 본 종류와 다르면 `CONTRACT_INTAKE_KIND_MISMATCH`.
 * - 생성 조건([open] — D-FLOW-003 ①, [rev 0.4] D-GOV-014 ①): 열린 건이 있으면 `CONTRACT_INTAKE_OPEN_EXISTS`. 완료 건은 막지 않는다. 사업자 DRAFT에서도 생성할 수 있다.
 * - 전이([transitionTo]): [ContractIntakeStatus.allowedTargets]의 전이만. 판정 순서: 사업자 생명주기(`BUSINESS_NOT_ACTIVE`) → version(`CONTRACT_INTAKE_VERSION_CONFLICT`) →
 *   전이 표(`CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED`) → 입력(MINOR-1: 사유·근거) → 계약 완료 효과(사업자 계약 규칙 — [BusinessContract]).
 * - [version]은 낙관적 잠금 기대값(저장된 행의 version) — 전이 결과는 정확히 +1이다. 사업자 version은 바꾸지 않는다(리뷰 m-4).
 * - [migrated] 건은 접수 항목이 없다([items] null — 근거·담당자 '이관' 표시, R-6). 이관 건은 순번 1·계약 완료·신규 종류뿐이다.
 */
@Suppress("LongParameterList")
class ContractIntake private constructor(
    val businessId: BusinessId,
    val seq: Int,
    val status: ContractIntakeStatus,
    val migrated: Boolean,
    val items: ContractIntakeItems?,
    val createdAt: Instant,
    val createdBy: ActorRef,
    val updatedAt: Instant,
    val updatedBy: ActorRef,
    val version: Long,
    val kind: ContractIntakeKind,
    val targetContractSeq: Int?,
) {
    init {
        require(seq >= 1) { "intake sequence starts at 1" }
        require(version >= 0) { "intake version must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not precede createdAt" }
        require(migrated == (items == null)) { "only a migrated intake has no intake items" }
        require(!migrated || (seq == 1 && status in MIGRATED_STATUSES && kind == ContractIntakeKind.NEW)) { "a migrated intake is intake 1, COMPLETED, NEW" }
        require((kind == ContractIntakeKind.NEW) == (targetContractSeq == null)) { "a CHANGE intake targets a contract and a NEW intake does not" }
        require(targetContractSeq == null || targetContractSeq >= 1) { "contract sequence starts at 1" }
    }

    fun transitionTo(
        business: Business,
        target: ContractIntakeStatus,
        expectedVersion: Long,
        input: ContractIntakeTransitionInput,
        at: Instant,
        by: ActorRef,
    ): ContractIntakeChange {
        require(business.id == businessId) { "intake belongs to another business" }
        business.requireActive()
        if (expectedVersion != version) throw MasterRuleViolation(MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT)
        if (!status.canMoveTo(target)) throw MasterRuleViolation(MasterRejection.CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED)
        val completes = target == ContractIntakeStatus.COMPLETED
        require(completes == (input.completion != null)) { "only a completion carries completion input" }
        requireInput(target, input)
        val recordedAt = if (at.isBefore(updatedAt)) updatedAt else at
        val contract = input.completion?.let { complete(business, it, checkNotNull(input.document), input.reason, at, by) }
        val next = ContractIntake(businessId, seq, target, migrated, items, createdAt, createdBy, recordedAt, by, version + 1, kind, targetContractSeq)
        return ContractIntakeChange(next, ContractIntakeTransition(businessId, seq, status, target, false, recordedAt, by, input.reason, input.document), contract)
    }

    /** MINOR-1 입력 검사(DB CHECK는 이중 방어로 남는다). */
    private fun requireInput(target: ContractIntakeStatus, input: ContractIntakeTransitionInput) {
        if (ContractIntakeTransition.requiresReason(status, target) && input.reason == null) throw MasterRuleViolation(MasterRejection.CONTRACT_INTAKE_REASON_REQUIRED)
        if (target == ContractIntakeStatus.COMPLETED) {
            if (input.document == null) throw MasterRuleViolation(MasterRejection.CONTRACT_INTAKE_DOCUMENT_REQUIRED)
        } else if (input.document != null) {
            throw MasterRuleViolation(MasterRejection.CONTRACT_INTAKE_DOCUMENT_NOT_ALLOWED)
        }
    }

    /**
     * 계약 완료 효과(§4B.6): 신규 = 최신 계약이 없거나 해지·만료인지 다시 확인 → 새 계약(순번 = 최신 + 1) + 버전 1. 변경 = 대상이 그 사업자의 최신 계약이고 해지·만료가 아니어야
     * 한다(아니면 `BUSINESS_CONTRACT_NOT_CHANGEABLE` — BR-BIZ-333, 접수 건은 열린 채 남는다) → 새 버전 + 직전 버전 닫기.
     */
    private fun complete(
        business: Business,
        completion: ContractIntakeCompletion,
        document: ContractDocumentRef,
        reason: BusinessChangeReason?,
        at: Instant,
        by: ActorRef,
    ): BusinessContractChange {
        val latest = completion.latestContract
        require(latest == null || latest.id.businessId == businessId) { "latest contract belongs to another business" }
        return when (kind) {
            ContractIntakeKind.NEW -> BusinessContract.create(business, latest, seq, completion.terms, document, reason, at, by)
            ContractIntakeKind.CHANGE -> {
                if (latest == null || latest.id.contractSeq != targetContractSeq) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_NOT_CHANGEABLE)
                latest.addVersion(business, seq, completion.terms, document, reason, at, by)
            }
        }
    }

    override fun toString(): String =
        "ContractIntake(businessId=${businessId.value}, seq=$seq, status=$status, kind=$kind, migrated=$migrated, version=$version)"

    companion object {
        private val MIGRATED_STATUSES = setOf(ContractIntakeStatus.COMPLETED)

        /**
         * 새 접수 건(상담 중, version 0). [latest] = 그 사업자의 최신 접수 건, [latestContract] = 최신 사업자 계약(둘 다 사업자 행 잠금 뒤 같은 TX에서 읽은 값, 없으면 null).
         * 판정 순서: 사업자 생명주기 → 열린 건(`CONTRACT_INTAKE_OPEN_EXISTS`) → 종류 판정(BR-BIZ-325) ≠ [expectedKind]면 `CONTRACT_INTAKE_KIND_MISMATCH`.
         */
        fun open(
            business: Business,
            latest: ContractIntake?,
            latestContract: BusinessContract?,
            expectedKind: ContractIntakeKind,
            items: ContractIntakeItems,
            at: Instant,
            by: ActorRef,
        ): ContractIntakeChange {
            business.requireActive()
            if (latest != null) {
                require(latest.businessId == business.id) { "latest intake belongs to another business" }
                if (latest.status.isOpen) throw MasterRuleViolation(MasterRejection.CONTRACT_INTAKE_OPEN_EXISTS)
            }
            require(latestContract == null || latestContract.id.businessId == business.id) { "latest contract belongs to another business" }
            val target = latestContract?.takeIf { it.hasContractAt(at) }?.id?.contractSeq
            val kind = if (target == null) ContractIntakeKind.NEW else ContractIntakeKind.CHANGE
            if (kind != expectedKind) throw MasterRuleViolation(MasterRejection.CONTRACT_INTAKE_KIND_MISMATCH)
            val seq = (latest?.seq ?: 0) + 1
            val intake = ContractIntake(business.id, seq, ContractIntakeStatus.CONSULTING, false, items, at, by, at, by, 0, kind, target)
            return ContractIntakeChange(intake, ContractIntakeTransition(business.id, seq, null, ContractIntakeStatus.CONSULTING, false, at, by))
        }

        /** persistence Mapper 전용 복원(이관 건 포함 — 이관 건은 migration SQL만 만든다, §4A.9). 종류 기본값은 신규(이관 건·FLOW-1 행 모양). */
        fun reconstitute(
            businessId: BusinessId,
            seq: Int,
            status: ContractIntakeStatus,
            migrated: Boolean,
            items: ContractIntakeItems?,
            createdAt: Instant,
            createdBy: ActorRef,
            updatedAt: Instant,
            updatedBy: ActorRef,
            version: Long,
            kind: ContractIntakeKind = ContractIntakeKind.NEW,
            targetContractSeq: Int? = null,
        ) = ContractIntake(businessId, seq, status, migrated, items, createdAt, createdBy, updatedAt, updatedBy, version, kind, targetContractSeq)
    }
}
