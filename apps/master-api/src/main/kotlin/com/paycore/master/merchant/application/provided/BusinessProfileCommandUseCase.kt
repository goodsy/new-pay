package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EvidenceRef

/**
 * 사업자 기본정보 명령(BUSINESS-PROFILE-001 §6.7 — BIZ-1 Application, web은 BIZ-2). 각 명령은 MASTER 로컬 TX 하나(`admin_request` 멱등·업무 변경·`master_change_history`·Outbox).
 * 결과는 기존 [AdminCommandResult] 틀: 성공 reference = businessId, 거절은 저장·재생(중복 거절만 reference = 기존 businessId — D-BIZ-003 응답 요약의 근거).
 * 값 규칙 위반은 저장된 거절이 아니라 `BusinessProfileRuleViolation`(TX 전체 rollback — `admin_request` 0행)이다(C-16).
 */
interface BusinessProfileCommandUseCase {
    /** DRAFT(D-BIZ-002 최소) 또는 REGISTERED로 생성. 같은 번호가 있으면 `BUSINESS_REGISTRATION_NUMBER_DUPLICATE`(reference = 기존 ID). */
    fun create(command: CreateBusinessCommand): AdminCommandResult

    /** 같은 상태 안 수정(전체 교체 — 번호·상태 없음). */
    fun update(command: UpdateBusinessCommand): AdminCommandResult

    /** DRAFT → REGISTERED(보낸 전체 내용 기준 — 같은 내용도 성공, M-1). */
    fun completeRegistration(command: CompleteBusinessRegistrationCommand): AdminCommandResult
}

/** [registrationStatus] = 생성 목표(DRAFT|REGISTERED). [profile]은 요청 순서 그대로(지문은 이 순서 — 저장은 정규 순서). */
data class CreateBusinessCommand(
    val request: AdminRequest,
    val registrationStatus: BusinessRegistrationStatus,
    val businessType: BusinessType,
    val registrationNumber: BusinessRegistrationNumber,
    val displayName: DisplayName,
    val profile: BusinessProfile,
) {
    init {
        require(registrationStatus == BusinessRegistrationStatus.DRAFT || registrationStatus == BusinessRegistrationStatus.REGISTERED) {
            "a business is created as DRAFT or REGISTERED"
        }
    }
}

/** [version] = 상세에서 읽은 기대 version(낙관적 잠금 — 다르면 `BUSINESS_VERSION_CONFLICT`). [reason] = 중요정보 변경 사유(§4.5 — 없으면 null). */
data class UpdateBusinessCommand(
    val request: AdminRequest,
    val businessId: BusinessId,
    val version: Long,
    val reason: BusinessChangeReason?,
    val businessType: BusinessType,
    val displayName: DisplayName,
    val profile: BusinessProfile,
    /** BIZ-CRIT-APPROVAL-001 BR-BIZ-391(R3-m2): 1인 경로 선택 근거 참조. 원문은 `business_change_evidence`에만, 이력 detail은 `evidence=PRESENT|ABSENT`. */
    val evidenceRef: EvidenceRef? = null,
) {
    init {
        require(version >= 0) { "version must not be negative" }
    }
}

/** 등록 완료(update와 같은 필드 — 목표 = REGISTERED). */
data class CompleteBusinessRegistrationCommand(
    val request: AdminRequest,
    val businessId: BusinessId,
    val version: Long,
    val reason: BusinessChangeReason?,
    val businessType: BusinessType,
    val displayName: DisplayName,
    val profile: BusinessProfile,
    /** BIZ-CRIT-APPROVAL-001 BR-BIZ-391(R3-m2): 1인 경로 선택 근거 참조. 원문은 `business_change_evidence`에만, 이력 detail은 `evidence=PRESENT|ABSENT`. */
    val evidenceRef: EvidenceRef? = null,
) {
    init {
        require(version >= 0) { "version must not be negative" }
    }
}