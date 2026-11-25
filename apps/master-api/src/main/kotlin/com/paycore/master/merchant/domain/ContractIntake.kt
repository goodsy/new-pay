package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 계약 접수 건 Aggregate(MERCHANT-FLOW-001 §4A.3 — MASTER 소유, 사업자 1:N). 순수 Domain: Spring·JPA·jOOQ에 의존하지 않는다(규칙은 여기, DB 행 모양은
 * `JpaContractIntakeEntity` — §4A.15).
 *
 * - 식별: (사업자, 생성 순번 [seq]). 순번은 사업자 안 단조 증가(1, 2, …)이고 "최신 접수 건" = 순번이 가장 큰 건이다(리뷰 m-3). 이관 건은 순번 1(§4A.9).
 * - 생성 조건([open] — D-FLOW-003 ①, D-FLOW-004 ①): 최신 접수 건이 없거나 계약 불가·계약 해지일 때만. 열린 건이 있으면 `CONTRACT_INTAKE_OPEN_EXISTS`,
 *   계약 완료 건이 있으면 `CONTRACT_INTAKE_COMPLETED_EXISTS`(해지 뒤에만 새 접수). 사업자 DRAFT에서도 생성할 수 있다(가맹점 등록 관문은 FLOW-3).
 * - 전이([transitionTo]): [ContractIntakeStatus.allowedTargets]의 전이만. 그 밖·같은 상태·종결 뒤 되돌리기는 `CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED`.
 *   판정 순서: 사업자 생명주기(`BUSINESS_NOT_ACTIVE`) → version(`CONTRACT_INTAKE_VERSION_CONFLICT`) → 전이 표.
 * - [version]은 낙관적 잠금 기대값(저장된 행의 version) — 전이 결과는 정확히 +1이다. 사업자 version은 바꾸지 않는다(리뷰 m-4).
 * - [migrated] 건은 접수 항목이 없다([items] null — 근거·담당자 '이관' 표시, R-6). 이관이 아닌 건은 접수 항목이 반드시 있다.
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
) {
    init {
        require(seq >= 1) { "intake sequence starts at 1" }
        require(version >= 0) { "intake version must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not precede createdAt" }
        require(migrated == (items == null)) { "only a migrated intake has no intake items" }
        require(!migrated || (seq == 1 && status in MIGRATED_STATUSES)) { "a migrated intake is intake 1, COMPLETED or later TERMINATED" }
    }

    fun transitionTo(business: Business, target: ContractIntakeStatus, expectedVersion: Long, at: Instant, by: ActorRef): ContractIntakeChange {
        require(business.id == businessId) { "intake belongs to another business" }
        business.requireActive()
        if (expectedVersion != version) throw MasterRuleViolation(MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT)
        if (!status.canMoveTo(target)) throw MasterRuleViolation(MasterRejection.CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED)
        val recordedAt = if (at.isBefore(updatedAt)) updatedAt else at
        val next = ContractIntake(businessId, seq, target, migrated, items, createdAt, createdBy, recordedAt, by, version + 1)
        return ContractIntakeChange(next, ContractIntakeTransition(businessId, seq, status, target, false, recordedAt, by))
    }

    override fun toString(): String = "ContractIntake(businessId=${businessId.value}, seq=$seq, status=$status, migrated=$migrated, version=$version)"

    companion object {
        private val MIGRATED_STATUSES = setOf(ContractIntakeStatus.COMPLETED, ContractIntakeStatus.TERMINATED)

        /**
         * 새 접수 건(상담 중, version 0). [latest] = 그 사업자의 최신 접수 건(사업자 행 잠금 뒤 같은 TX에서 읽은 값, 없으면 null).
         * 판정 순서: 사업자 생명주기 → 최신 건 상태(열린 건 → 완료 건).
         */
        fun open(business: Business, latest: ContractIntake?, items: ContractIntakeItems, at: Instant, by: ActorRef): ContractIntakeChange {
            business.requireActive()
            if (latest != null) {
                require(latest.businessId == business.id) { "latest intake belongs to another business" }
                if (latest.status.isOpen) throw MasterRuleViolation(MasterRejection.CONTRACT_INTAKE_OPEN_EXISTS)
                if (latest.status == ContractIntakeStatus.COMPLETED) throw MasterRuleViolation(MasterRejection.CONTRACT_INTAKE_COMPLETED_EXISTS)
            }
            val seq = (latest?.seq ?: 0) + 1
            val intake = ContractIntake(business.id, seq, ContractIntakeStatus.CONSULTING, false, items, at, by, at, by, 0)
            return ContractIntakeChange(intake, ContractIntakeTransition(business.id, seq, null, ContractIntakeStatus.CONSULTING, false, at, by))
        }

        /** persistence Mapper 전용 복원(이관 건 포함 — 이관 건은 migration SQL만 만든다, §4A.9). */
        @Suppress("LongParameterList")
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
        ) = ContractIntake(businessId, seq, status, migrated, items, createdAt, createdBy, updatedAt, updatedBy, version)
    }
}
