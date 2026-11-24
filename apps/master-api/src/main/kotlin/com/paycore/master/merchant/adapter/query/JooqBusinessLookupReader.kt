package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.BUSINESS
import com.paycore.master.merchant.application.provided.BusinessSummary
import com.paycore.master.merchant.application.required.BusinessLookupReader
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * 사업자등록번호 정확 일치(BUSINESS-PROFILE-001 §6.3 — 합친 MERCHANT-BIZ-LOOKUP-001, jOOQ 읽기 전용). `WHERE registration_number = ?`(바인드 — UNIQUE 색인, 0/1행).
 * SELECT = businessId·상호·원문 번호(BRN-UNMASK-001 — D-BIZ-007, 요청에 이미 있는 값)·등록 상태(D-BIZ-006 ③). 명령 측 `findIdByRegistrationNumber`를 재사용하지 않는다(CQRS).
 */
@Component
class JooqBusinessLookupReader(private val dsl: DSLContext) : BusinessLookupReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun lookup(number: BusinessRegistrationNumber): BusinessSummary? =
        dsl.select(BUSINESS.BUSINESS_ID, BUSINESS.DISPLAY_NAME, MasterSqlText.registrationNumber(), BUSINESS.REGISTRATION_STATUS)
            .from(BUSINESS)
            .where(BUSINESS.REGISTRATION_NUMBER.eq(number.value))
            .fetchOne { BusinessSummary(it.value1(), it.value2(), it.value3(), it.value4()) }
}
