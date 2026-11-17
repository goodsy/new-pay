package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessRegistrationNumber

/**
 * 사업자 Aggregate 저장 Port. 두 종류 읽기(BUSINESS-PROFILE-001 m-3):
 * - **Root 전용**([lock]·[find]·[findIdByRegistrationNumber]) — 하위 표를 읽지 않는다(가맹점·계약 등록·권리 원천 경로의 SQL 수·잠금 범위 불변).
 * - **profile 포함**([lockWithProfile]) — 사업자 명령(BusinessProfileService) 전용.
 */
interface BusinessRepository {
    /** 옛 등록 경로(과도기 — X-10): Root + 등록정보 1행(국가 KR, 하위 행 없음). [insert](business, [BusinessProfile.EMPTY])와 같다. */
    fun insert(business: Business)

    /** Root + 등록정보 1행 + 현재 하위 행(입력 순서). 번호 중복은 flush에서 `BUSINESS_REGISTRATION_NUMBER`(업무 유일키)로 드러난다. */
    fun insert(business: Business, profile: BusinessProfile)

    fun lock(id: BusinessId): Business?

    /** business 행 FOR UPDATE → 같은 TX에서 현재 하위 행 일반 SELECT(하위 행은 business 잠금으로 직렬화 — 별도 잠금 없음). */
    fun lockWithProfile(id: BusinessId): BusinessWithProfile?

    /**
     * 전이 결과 저장(diff — §5.5): [before]와 [after]를 하위 행 동일성(내용 + 순서)으로 짝지어 사라진 행 종료(effective_to·ended_by) → flush → 새 행 persist,
     * 등록정보 제자리, business 행 갱신(version 정확히 +1 — [business].version은 저장 후 기대 version). [before]는 잠근 뒤 읽은 현재 profile이어야 한다.
     */
    fun update(business: Business, before: BusinessProfile, after: BusinessProfile)

    /** 같은 등록번호의 기존 Business(D-MD-008 중복 판정). 잠금 없음 — 동시 등록 경합은 UNIQUE 충돌 → 재판정으로 수렴한다. */
    fun findIdByRegistrationNumber(number: BusinessRegistrationNumber): BusinessId?

    /** 잠금 없는 읽기(Snapshot 조립 — Business 상태 변경 명령은 그 Business의 Merchant를 모두 잠근다, 명세 §3.2). */
    fun find(id: BusinessId): Business?
}

/** 잠근 사업자 Root와 현재 profile(Application 반환 묶음 — 전이 메서드는 둘을 함께 받는다). */
data class BusinessWithProfile(val business: Business, val profile: BusinessProfile)
