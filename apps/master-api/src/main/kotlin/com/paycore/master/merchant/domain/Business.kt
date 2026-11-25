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
     * 같은 상태 안 수정(DRAFT → DRAFT, REGISTERED → REGISTERED — 명세 §4.4·§4.5). 판정 순서: 생명주기 → 사용중지(`BUSINESS_INACTIVE` — BR-BIZ-311) → version → 변경 계산(없으면 `BUSINESS_PROFILE_UNCHANGED`,
     * 중요 변경 + 사유 없음 `BUSINESS_CHANGE_REASON_REQUIRED`) → 현재 상태 규칙 재검사(REGISTERED는 필수값을 지우는 수정 거절 — 위반은 [BusinessProfileRuleViolation]).
     */
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
     * DRAFT → REGISTERED(명세 §4.4 — M-1: 상태 전이 자체가 변경이므로 `BUSINESS_PROFILE_UNCHANGED`를 적용하지 않는다). 판정 순서: 생명주기 → DRAFT만(`BUSINESS_NOT_DRAFT`)
     * → version → 내용 중요 변경 + 사유 없음 → REGISTERED 규칙(보낸 전체 내용 기준).
     */
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
