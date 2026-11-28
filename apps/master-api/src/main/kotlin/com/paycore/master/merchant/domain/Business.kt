package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 법적 사업 주체. 하나의 Business가 복수 Merchant를 가질 수 있다(초기). 계약 당사자(D-MD-004).
 *
 * BUSINESS-PROFILE-001(명세 §4): 상태는 두 축이다(D-BIZ-001) — 생명주기 [lifecycle](ACTIVE/TERMINATED, D-MD-006 불변)과 등록 상태 [registrationStatus]
 * (DRAFT/REGISTERED/INACTIVE). 진위확인 [verificationStatus]는 NOT_CHECKED만(TC-21). 사업자등록번호는 생성 후 바뀌지 않는다(TBD-04·D-BIZ-005 — 전이 메서드에 번호 인자가 없다).
 * [businessType] null은 migration·과도기 옛 등록 경로 행만이다(X-1·X-10 — 그 행의 첫 수정·등록 완료에서 구분 입력이 강제된다).
 * 하위 정보([BusinessProfile])는 Root와 따로 들고(Root 전용 읽기 경로 유지 — m-3), 전이 메서드가 현재 profile을 함께 받는다.
 * [version]은 낙관적 잠금 기대값(저장된 행의 version) — 전이 결과는 정확히 +1이다.
 */
@Suppress("LongParameterList")
class Business private constructor(
    val id: BusinessId,
    val registrationNumber: BusinessRegistrationNumber,
    val displayName: DisplayName,
    val businessType: BusinessType?,
    val registrationStatus: BusinessRegistrationStatus,
    val verificationStatus: BusinessVerificationStatus,
    val lifecycle: Lifecycle,
    val createdAt: Instant,
    val createdBy: ActorRef,
    val updatedAt: Instant,
    val updatedBy: ActorRef,
    val version: Long,
) {
    init {
        require(version >= 0) { "business version must not be negative" }
        require(registrationStatus == BusinessRegistrationStatus.DRAFT || businessType != null) { "a non-DRAFT business must have a business type" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not precede createdAt" }
    }

    fun requireActive() {
        if (lifecycle != Lifecycle.ACTIVE) throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_ACTIVE)
    }

    /** D-BIZ-001: 새 가맹점 등록은 REGISTERED 사업자만. `Merchant.register`가 `requireActive()` 다음에 부른다(BIZ-4 전환 — C-27). */
    fun requireRegistered() {
        if (registrationStatus != BusinessRegistrationStatus.REGISTERED) throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_REGISTERED)
    }

    /**
     * **과도 경로(BIZ-CRIT-APPROVAL-001 S2에서 제거 — Coordinator 결정)**: 계약·가맹점 이력 없이 판정하는 옛 1인 수정 — 현재 Application(`BusinessProfileService`)만 부른다. S2가 잠금 뒤 이력 판정과 함께
     * `history`를 받는 오버로드로 바꾸고 이 메서드를 지운다(S1은 Domain만 — 명세 §8). 동작은 변경 전과 같다.
     *
     * 같은 상태 안 수정(DRAFT → DRAFT, REGISTERED → REGISTERED — 명세 §4.4·§4.5). 판정 순서: 생명주기 → 사용중지(`BUSINESS_INACTIVE` — BR-BIZ-311) → version → 변경 계산(없으면 `BUSINESS_PROFILE_UNCHANGED`,
     * 중요 변경 + 사유 없음 `BUSINESS_CHANGE_REASON_REQUIRED`) → 현재 상태 규칙 재검사(REGISTERED는 필수값을 지우는 수정 거절 — 위반은 [BusinessProfileRuleViolation]).
     */
    @Deprecated("BIZ-CRIT-APPROVAL-001: 계약·가맹점 이력을 받는 오버로드를 쓴다(S2에서 제거)", level = DeprecationLevel.WARNING)
    @Suppress("LongParameterList")
    fun updateProfile(
        current: BusinessProfile,
        type: BusinessType,
        name: DisplayName,
        profile: BusinessProfile,
        reason: BusinessChangeReason?,
        expectedVersion: Long,
        at: Instant,
        by: ActorRef,
    ): BusinessProfileEdit {
        requireActive()
        // BR-BIZ-311(D-GOV-005 ②, BIZ-NORM-002 M-3): 사용중지 사업자 수정은 저장되는 업무 거절(전이 경로는 생명주기 작업 — TBD-08). version·내용보다 먼저 판정한다.
        if (registrationStatus == BusinessRegistrationStatus.INACTIVE) throw MasterRuleViolation(MasterRejection.BUSINESS_INACTIVE)
        requireVersion(expectedVersion)
        val change = BusinessProfileChange.of(registrationStatus, BusinessContent(businessType, displayName, current), BusinessContent(type, name, profile))
        if (change.isEmpty) throw MasterRuleViolation(MasterRejection.BUSINESS_PROFILE_UNCHANGED)
        requireReason(change, reason)
        BusinessProfileRules.require(type, registrationNumber, name, profile, registrationStatus)
        return BusinessProfileEdit(next(type, name, registrationStatus, monotonic(at), by), profile.canonical(), change)
    }

    /**
     * **과도 경로(BIZ-CRIT-APPROVAL-001 S2에서 제거)** — 위 [updateProfile] 옛 오버로드와 같다.
     *
     * DRAFT → REGISTERED(명세 §4.4 — M-1: 상태 전이 자체가 변경이므로 `BUSINESS_PROFILE_UNCHANGED`를 적용하지 않는다). 판정 순서: 생명주기 → DRAFT만(`BUSINESS_NOT_DRAFT`)
     * → version → 내용 중요 변경 + 사유 없음 → REGISTERED 규칙(보낸 전체 내용 기준).
     */
    @Deprecated("BIZ-CRIT-APPROVAL-001: 계약·가맹점 이력을 받는 오버로드를 쓴다(S2에서 제거)", level = DeprecationLevel.WARNING)
    @Suppress("LongParameterList")
    fun completeRegistration(
        current: BusinessProfile,
        type: BusinessType,
        name: DisplayName,
        profile: BusinessProfile,
        reason: BusinessChangeReason?,
        expectedVersion: Long,
        at: Instant,
        by: ActorRef,
    ): BusinessProfileEdit {
        requireActive()
        if (registrationStatus != BusinessRegistrationStatus.DRAFT) throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_DRAFT)
        requireVersion(expectedVersion)
        val content = BusinessProfileChange.of(registrationStatus, BusinessContent(businessType, displayName, current), BusinessContent(type, name, profile))
        requireReason(content, reason)
        BusinessProfileRules.require(type, registrationNumber, name, profile, BusinessRegistrationStatus.REGISTERED)
        val change = content.withStatusTransition(BusinessRegistrationStatus.DRAFT, BusinessRegistrationStatus.REGISTERED)
        return BusinessProfileEdit(next(type, name, BusinessRegistrationStatus.REGISTERED, monotonic(at), by), profile.canonical(), change)
    }

    /**
     * BIZ-CRIT-APPROVAL-001 §3.3 1인 경로 수정(§1.1 인원 판정 — D-GOV-016 ③·D-GOV-025·D-GOV-026). [history]는 Application이 **사업자 행 잠금 뒤** 판정한 계약·가맹점 이력이다.
     * 판정 순서: 생명주기 → 사용중지(`BUSINESS_INACTIVE`) → version → 변경 계산 → 변경 없음 → **정정 차단**(`BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED`) → **2인 필요**
     * (`BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED` — 사유가 있어도) → 사유(1인 + 사유 칸) → 현재 상태 규칙. 규칙 위반(400)보다 승인 필요(저장 거절)가 먼저다.
     */
    @Suppress("LongParameterList")
    fun updateProfile(
        current: BusinessProfile,
        type: BusinessType,
        name: DisplayName,
        profile: BusinessProfile,
        reason: BusinessChangeReason?,
        expectedVersion: Long,
        history: BusinessContractMerchantHistory,
        at: Instant,
        by: ActorRef,
    ): BusinessProfileEdit {
        requireActive()
        if (registrationStatus == BusinessRegistrationStatus.INACTIVE) throw MasterRuleViolation(MasterRejection.BUSINESS_INACTIVE)
        requireVersion(expectedVersion)
        val change = BusinessProfileChange.of(registrationStatus, BusinessContent(businessType, displayName, current), BusinessContent(type, name, profile))
        if (change.isEmpty) throw MasterRuleViolation(MasterRejection.BUSINESS_PROFILE_UNCHANGED)
        requireSinglePerson(BusinessChangeApprovalLevel.of(registrationStatus, change, history), reason)
        BusinessProfileRules.require(type, registrationNumber, name, profile, registrationStatus)
        return BusinessProfileEdit(next(type, name, registrationStatus, monotonic(at), by), profile.canonical(), change)
    }

    /**
     * BIZ-CRIT-APPROVAL-001 §3.3 등록 완료(1인 경로 — §1.1은 DRAFT 행으로 판정). 판정 순서: 생명주기 → DRAFT만 → version → 내용 변경 계산 → 정정 차단 → 2인 필요 → 사유 → REGISTERED 규칙.
     * 2인 칸 변경이 섞인 등록 완료는 한 요청으로 할 수 없다(BR-BIZ-395 — 근거 경로는 수정만 실행한다).
     */
    @Suppress("LongParameterList")
    fun completeRegistration(
        current: BusinessProfile,
        type: BusinessType,
        name: DisplayName,
        profile: BusinessProfile,
        reason: BusinessChangeReason?,
        expectedVersion: Long,
        history: BusinessContractMerchantHistory,
        at: Instant,
        by: ActorRef,
    ): BusinessProfileEdit {
        requireActive()
        if (registrationStatus != BusinessRegistrationStatus.DRAFT) throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_DRAFT)
        requireVersion(expectedVersion)
        val content = BusinessProfileChange.of(registrationStatus, BusinessContent(businessType, displayName, current), BusinessContent(type, name, profile))
        requireSinglePerson(BusinessChangeApprovalLevel.of(registrationStatus, content, history), reason)
        BusinessProfileRules.require(type, registrationNumber, name, profile, BusinessRegistrationStatus.REGISTERED)
        val change = content.withStatusTransition(BusinessRegistrationStatus.DRAFT, BusinessRegistrationStatus.REGISTERED)
        return BusinessProfileEdit(next(type, name, BusinessRegistrationStatus.REGISTERED, monotonic(at), by), profile.canonical(), change)
    }

    /**
     * BIZ-CRIT-APPROVAL-001 §3.3 제안(2인 칸만). 사업자·하위 행·version은 바뀌지 않는다(이 Root는 불변 — 반환은 제안뿐, P-10). 판정 순서: 생명주기 → 사용중지 → version → 변경 계산 →
     * 변경 없음 → 정정 차단 → 2인 칸 아님(`BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED` — 제안 때만) → 사유 필수 → 현재 상태 규칙. 근거 참조는 필수 값 타입이다(P-4 — BR-BIZ-382).
     */
    @Suppress("LongParameterList")
    fun proposeCriticalChange(
        current: BusinessProfile,
        type: BusinessType,
        name: DisplayName,
        profile: BusinessProfile,
        reason: BusinessChangeReason?,
        evidenceRef: EvidenceRef,
        expectedVersion: Long,
        history: BusinessContractMerchantHistory,
        proposalId: BusinessChangeProposalId,
        at: Instant,
        by: ActorRef,
    ): BusinessChangeProposal {
        requireActive()
        if (registrationStatus == BusinessRegistrationStatus.INACTIVE) throw MasterRuleViolation(MasterRejection.BUSINESS_INACTIVE)
        requireVersion(expectedVersion)
        val change = BusinessProfileChange.of(registrationStatus, BusinessContent(businessType, displayName, current), BusinessContent(type, name, profile))
        if (change.isEmpty) throw MasterRuleViolation(MasterRejection.BUSINESS_PROFILE_UNCHANGED)
        val level = BusinessChangeApprovalLevel.of(registrationStatus, change, history)
        if (level == BusinessChangeApprovalLevel.BLOCKED) throw MasterRuleViolation(MasterRejection.BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED)
        if (!level.requiresApproval) throw MasterRuleViolation(MasterRejection.BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED)
        val why = reason ?: throw MasterRuleViolation(MasterRejection.BUSINESS_CHANGE_REASON_REQUIRED)
        BusinessProfileRules.require(type, registrationNumber, name, profile, registrationStatus)
        return BusinessChangeProposal.of(proposalId, id, version, BusinessContent(type, name, profile), change, why, evidenceRef, by, at)
    }

    /**
     * BIZ-CRIT-APPROVAL-001 §3.3 C 근거 실행(실행기가 근거 판정 1~4를 끝낸 뒤, 사업자 잠금·lockedNow 뒤). [recomputedDigest]는 Application이 저장된 제안의
     * [BusinessChangeProposal.digestInput]을 MASTER 키로 **다시 계산한** 값이다. 판정 순서:
     * 1 제안 ↔ 승인 명령(proposalId·businessId·이 사업자·baseVersion·digest) 불일치 `PROPOSAL_CONTENT_MISMATCH` → 2 제안 요청자 ≠ 근거 요청자 `PROPOSAL_REQUESTER_MISMATCH`(M-6)
     * → 3 생명주기·사용중지 → 4 version ≠ baseVersion `BUSINESS_VERSION_CONFLICT` → 4a 재계산 변경 요약 ≠ 제안 요약(항목·중요 항목·정정·대표자) = 무결성 결함([IllegalStateException] — 빈 변경 포함) → 5 **차단 칸만** `BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED`(대기 중 이력이 생긴 정정) → 6 규칙.
     * **인원은 다시 판정하지 않는다**(R2-m1 — BR-BIZ-399): 승인된 2인 변경을 "이제 2인이 아니다"로 거절하지 않는다. §1.1 판정은 약해지지 않으므로 1인 칸이 보이면 무결성 결함([IllegalStateException]).
     * 갱신 행위자는 승인자다(가맹점 코드 변경 선례). 사업자등록번호는 바뀌지 않는다(인자 없음).
     */
    fun applyApprovedChange(
        current: BusinessProfile,
        proposal: BusinessChangeProposal,
        approved: ApprovedBusinessChange,
        recomputedDigest: BusinessChangeDigest,
        history: BusinessContractMerchantHistory,
        at: Instant,
    ): BusinessProfileEdit {
        val sameProposal = proposal.id == approved.proposalId && proposal.businessId == approved.businessId && proposal.businessId == id &&
            proposal.baseVersion == approved.baseVersion && approved.contentDigest == recomputedDigest
        if (!sameProposal) throw MasterRuleViolation(MasterRejection.PROPOSAL_CONTENT_MISMATCH)
        if (proposal.requestedBy != approved.evidence.requestedBy) throw MasterRuleViolation(MasterRejection.PROPOSAL_REQUESTER_MISMATCH)
        requireActive()
        if (registrationStatus == BusinessRegistrationStatus.INACTIVE) throw MasterRuleViolation(MasterRejection.BUSINESS_INACTIVE)
        requireVersion(proposal.baseVersion)
        val type = checkNotNull(proposal.content.businessType) { "proposal content must have a business type" }
        val change = BusinessProfileChange.of(registrationStatus, BusinessContent(businessType, displayName, current), proposal.content)
        check(proposal.summaryMatches(change)) { "integrity defect: the recomputed change does not match the approved proposal summary" }
        val level = BusinessChangeApprovalLevel.of(registrationStatus, change, history)
        if (level == BusinessChangeApprovalLevel.BLOCKED) throw MasterRuleViolation(MasterRejection.BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED)
        check(level.requiresApproval) { "integrity defect: an approved proposal now sits in a 1-person cell" }
        BusinessProfileRules.require(type, registrationNumber, proposal.content.displayName, proposal.content.profile, registrationStatus)
        return BusinessProfileEdit(
            next(type, proposal.content.displayName, registrationStatus, monotonic(at), approved.evidence.approvedBy), proposal.content.profile.canonical(), change,
        )
    }

    /** §1.1 1인 경로 관문: 차단 칸 → 2인 칸 → 1인 + 사유 칸의 사유 없음. */
    private fun requireSinglePerson(level: BusinessChangeApprovalLevel, reason: BusinessChangeReason?) {
        if (level == BusinessChangeApprovalLevel.BLOCKED) throw MasterRuleViolation(MasterRejection.BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED)
        if (level.requiresApproval) throw MasterRuleViolation(MasterRejection.BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED)
        if (level == BusinessChangeApprovalLevel.SINGLE_WITH_REASON && reason == null) throw MasterRuleViolation(MasterRejection.BUSINESS_CHANGE_REASON_REQUIRED)
    }

    /**
     * 전이 기록 시각 = max(판정 시각, 저장된 [updatedAt])(BIZ-1 리뷰 m-4). 서버 시계가 뒤로 가도(인스턴스 간 시계 차·NTP 보정) 갱신 시각과 하위 행 effective_from/to가
     * 거꾸로 가지 않는다 — 종료 행 effective_to ≥ effective_from(DB `ck_*_period`)이 시계와 무관하게 성립한다. 이력·Outbox의 occurredAt은 실행기 판정 시각 그대로다.
     */
    private fun monotonic(at: Instant): Instant = if (at.isBefore(updatedAt)) updatedAt else at

    private fun requireVersion(expected: Long) {
        if (expected != version) throw MasterRuleViolation(MasterRejection.BUSINESS_VERSION_CONFLICT)
    }

    private fun requireReason(change: BusinessProfileChange, reason: BusinessChangeReason?) {
        if (change.important && reason == null) throw MasterRuleViolation(MasterRejection.BUSINESS_CHANGE_REASON_REQUIRED)
    }

    private fun next(type: BusinessType, name: DisplayName, status: BusinessRegistrationStatus, at: Instant, by: ActorRef) = Business(
        id, registrationNumber, name, type, status, verificationStatus, lifecycle, createdAt, createdBy, at, by, version + 1,
    )

    override fun toString(): String =
        "Business(id=${id.value}, registrationNumber=${registrationNumber.masked()}, lifecycle=$lifecycle, registrationStatus=$registrationStatus)"

    companion object {
        /** 임시저장 생성(D-BIZ-002 — 구분 + 번호 + 상호 필수, 그 밖 선택). */
        @Suppress("LongParameterList")
        fun createDraft(
            id: BusinessId,
            type: BusinessType,
            registrationNumber: BusinessRegistrationNumber,
            displayName: DisplayName,
            profile: BusinessProfile,
            at: Instant,
            by: ActorRef,
        ): BusinessProfileEdit = create(id, type, registrationNumber, displayName, profile, BusinessRegistrationStatus.DRAFT, at, by)

        /** 등록 완료 상태로 바로 생성(지침서 §5 "DRAFT 또는 유효한 REGISTERED 생성" — REGISTERED 규칙). */
        @Suppress("LongParameterList")
        fun createRegistered(
            id: BusinessId,
            type: BusinessType,
            registrationNumber: BusinessRegistrationNumber,
            displayName: DisplayName,
            profile: BusinessProfile,
            at: Instant,
            by: ActorRef,
        ): BusinessProfileEdit = create(id, type, registrationNumber, displayName, profile, BusinessRegistrationStatus.REGISTERED, at, by)

        @Suppress("LongParameterList")
        private fun create(
            id: BusinessId,
            type: BusinessType,
            number: BusinessRegistrationNumber,
            name: DisplayName,
            profile: BusinessProfile,
            status: BusinessRegistrationStatus,
            at: Instant,
            by: ActorRef,
        ): BusinessProfileEdit {
            BusinessProfileRules.require(type, number, name, profile, status)
            val business = Business(id, number, name, type, status, BusinessVerificationStatus.NOT_CHECKED, Lifecycle.ACTIVE, at, by, at, by, 0)
            return BusinessProfileEdit(business, profile.canonical(), BusinessProfileChange.created(BusinessContent(type, name, profile)))
        }

        /** persistence Mapper 전용 복원. */
        @Suppress("LongParameterList")
        fun reconstitute(
            id: BusinessId,
            registrationNumber: BusinessRegistrationNumber,
            displayName: DisplayName,
            businessType: BusinessType?,
            registrationStatus: BusinessRegistrationStatus,
            verificationStatus: BusinessVerificationStatus,
            lifecycle: Lifecycle,
            createdAt: Instant,
            createdBy: ActorRef,
            updatedAt: Instant,
            updatedBy: ActorRef,
            version: Long,
        ) = Business(
            id, registrationNumber, displayName, businessType, registrationStatus, verificationStatus, lifecycle, createdAt, createdBy, updatedAt, updatedBy, version,
        )
    }
}
