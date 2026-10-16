package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber

interface BusinessRepository {
    fun insert(business: Business)

    fun lock(id: BusinessId): Business?

    /** 같은 등록번호의 기존 Business(D-MD-008 중복 판정). 잠금 없음 — 동시 등록 경합은 UNIQUE 충돌 → 재판정으로 수렴한다. */
    fun findIdByRegistrationNumber(number: BusinessRegistrationNumber): BusinessId?

    /** 잠금 없는 읽기(Snapshot 조립 — Business 상태 변경 명령은 그 Business의 Merchant를 모두 잠근다, 명세 §3.2). */
    fun find(id: BusinessId): Business?
}
