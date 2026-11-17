package com.paycore.admin.merchantops.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 화면 사업자 명령(BUSINESS-PROFILE-001 §7.2 — 생성·수정·등록 완료, MASTER의 같은 이름 Use Case와 모듈이 다르다). `MerchantRegistrationService` 순서 그대로:
 * 앞단(ACTIVE + 업무 조회 역할 — 아니면 403, 감사 없음) → 권한 상수(`BusinessPermissions.CREATE`·`UPDATE`·`COMPLETE` — 아니면 403 + 감사 `FORBIDDEN`) → 형식(`BusinessProfileInputRules` —
 * 400 + fieldErrors, MASTER 미호출) → 등록 창 → **create만 등록번호 버킷 예약** → 시도 감사 `REQUESTED` → MASTER(TX 밖, 키 `mo1.<계정>.<submissionId>`) → 버킷 확정/환불 → 결과 감사 → 응답.
 * 결과 불명([BusinessProfileResult.OutcomeUnknown]·[BusinessProfileResult.RetrySameKey]·[BusinessProfileResult.AuditUnavailable])은 **같은 submissionId로 다시** 보내 확인한다.
 */
interface BusinessProfileUseCase {
    fun execute(actorId: String, request: BusinessProfileRequest, context: AdminRequestContext): BusinessProfileResult

    /**
     * 본문이 엄격 해석에 실패했다(구조 위반 — 본문 원문은 받지 않는다). [businessId]는 경로 값(수정·등록 완료 — 형식이 맞을 때만 감사 대상에 쓴다).
     * create는 번호가 오가는 시도라 감사(`brn=INVALID` — D-MOPS-006 ④), update·등록 완료는 지표만(기존 "다른 종류" 관례). 역할이 형식보다 먼저다.
     */
    fun rejectMalformed(actorId: String, operation: BusinessProfileOperation, businessId: String?, context: AdminRequestContext): BusinessProfileResult
}

/** ADMIN 사업자 명령 종류(감사 action — §7.4, C-25 / MASTER 경로·operation — §6.1). */
enum class BusinessProfileOperation(val auditAction: String, val masterPath: String, val masterOperation: String) {
    CREATE("BUSINESS_CREATE", "create", "CreateBusiness"),
    UPDATE("BUSINESS_UPDATE", "update", "UpdateBusiness"),
    COMPLETE_REGISTRATION("BUSINESS_REGISTRATION_COMPLETE", "complete-registration", "CompleteBusinessRegistration"),
}
