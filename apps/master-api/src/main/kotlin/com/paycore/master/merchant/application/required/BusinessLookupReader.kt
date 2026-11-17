package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.BusinessSummary
import com.paycore.master.merchant.domain.BusinessRegistrationNumber

/**
 * 사업자등록번호 정확 일치 읽기 Port(BUSINESS-PROFILE-001 §6.3 — 합친 MERCHANT-BIZ-LOOKUP-001). 번호는 바인드 값으로만 쓰고 결과에는 마스킹 값만 담는다.
 * 명령 측 `BusinessRepository.findIdByRegistrationNumber`와 분리(CQRS).
 */
interface BusinessLookupReader {
    fun lookup(number: BusinessRegistrationNumber): BusinessSummary?
}
