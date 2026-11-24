package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.BUSINESS
import com.paycore.master.jooq.generated.Tables.BUSINESS_ADDRESS
import com.paycore.master.jooq.generated.Tables.BUSINESS_CATEGORY
import com.paycore.master.jooq.generated.Tables.BUSINESS_CONTACT
import com.paycore.master.jooq.generated.Tables.BUSINESS_REGISTRATION
import com.paycore.master.jooq.generated.Tables.BUSINESS_REPRESENTATIVE
import com.paycore.master.jooq.generated.Tables.MERCHANT
import com.paycore.master.merchant.application.provided.BusinessDetailView
import com.paycore.master.merchant.application.provided.BusinessSummary
import com.paycore.master.merchant.application.required.BusinessDetailReader
import com.paycore.master.merchant.domain.BusinessId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 사업자 상세(BUSINESS-PROFILE-001 §6.4, jOOQ — 한 읽기 전용 REPEATABLE READ TX라 business·등록정보·하위 행이 같은 시점이다). 하위 행은 **현재 행만**(`effective_to IS NULL`),
 * 표시 순서대로(업태 → 종목·유형 안 `display_order`, 사업장 → 실사업장). Aggregate를 로드하지 않는다(CQRS — 명령 측 `lockWithProfile`과 별개).
 * 개인정보·법인등록번호는 원문(D-BIZ-006 ② — 상세 전용), 사업자등록번호도 원문(BRN-UNMASK-001 — D-BIZ-007: 조회 응답 원문 10자리, 응답 필드 `registrationNumber` — v2 계약). 시각은 저장 UTC 문자열(TZ-FIX-001).
 * [summary]: 중복 거절 요약(§6.7 D-BIZ-003) — 상세와 같은 business 행에서 상호·원문 번호(D-BIZ-007·D-PII-001 ① 중복 안내)·등록 상태만.
 */
@Component
class JooqBusinessDetailReader(private val dsl: DSLContext) : BusinessDetailReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun read(businessId: BusinessId): BusinessDetailView? {
        val id = businessId.value
        val merchantCount = DSL.field(DSL.selectCount().from(MERCHANT).where(MERCHANT.BUSINESS_ID.eq(BUSINESS.BUSINESS_ID)))
        val business = dsl.select(
            BUSINESS.BUSINESS_ID, BUSINESS.DISPLAY_NAME, MasterSqlText.registrationNumber(), BUSINESS.BUSINESS_TYPE, BUSINESS.REGISTRATION_STATUS,
            BUSINESS.VERIFICATION_STATUS, BUSINESS.LIFECYCLE, BUSINESS.VERSION, MasterSqlText.instant(BUSINESS.CREATED_AT), MasterSqlText.instant(BUSINESS.UPDATED_AT),
            merchantCount,
        )
            .from(BUSINESS)
            .where(BUSINESS.BUSINESS_ID.eq(id))
            .fetchOne { r ->
                BusinessDetailView.BusinessBlock(
                    businessId = r.value1(), businessName = r.value2(), registrationNumber = r.value3(), businessType = r.value4(),
                    registrationStatus = r.value5(), verificationStatus = r.value6(), lifecycle = r.value7(), version = r.value8(),
                    createdAt = Instant.parse(r.value9()), updatedAt = Instant.parse(r.value10()), merchantCount = r.value11().toLong(),
                )
            } ?: return null

        val reg = BUSINESS_REGISTRATION
        val registrationRow = dsl.select(
            reg.REGISTRATION_ID, reg.CORPORATE_REGISTRATION_NUMBER, reg.OPENING_DATE, reg.COUNTRY_CODE, reg.ENGLISH_NAME, reg.PHONE_NUMBER, reg.EMAIL,
        ).from(reg).where(reg.BUSINESS_ID.eq(id)).fetchOne()
        // 1:1 불변식(TC-18 — 모든 business에 등록정보 정확히 1행). 없으면 결함 — 빈 값으로 꾸며 내보내지 않는다.
        checkNotNull(registrationRow) { "business without a registration row" }
        val registrationId = registrationRow.value1()
        val registration = BusinessDetailView.RegistrationBlock(
            corporateRegistrationNumber = registrationRow.value2(), openingDate = registrationRow.value3(), countryCode = registrationRow.value4(),
            englishName = registrationRow.value5(), phoneNumber = registrationRow.value6(), email = registrationRow.value7(),
        )

        val rep = BUSINESS_REPRESENTATIVE
        val representatives = dsl.select(rep.NAME, rep.REPRESENTATIVE_TYPE).from(rep)
            .where(rep.BUSINESS_ID.eq(id).and(rep.EFFECTIVE_TO.isNull))
            .orderBy(rep.DISPLAY_ORDER)
            .fetch { BusinessDetailView.Representative(it.value1(), it.value2()) }

        val cat = BUSINESS_CATEGORY
        val categories = dsl.select(cat.CATEGORY_TYPE, cat.NAME).from(cat)
            .where(cat.REGISTRATION_ID.eq(registrationId).and(cat.EFFECTIVE_TO.isNull))
            .orderBy(DSL.field("FIELD({0}, 'BUSINESS_TYPE', 'BUSINESS_ITEM')", Int::class.javaObjectType, cat.CATEGORY_TYPE), cat.DISPLAY_ORDER)
            .fetch { BusinessDetailView.Category(it.value1(), it.value2()) }

        val adr = BUSINESS_ADDRESS
        val addresses = dsl.select(adr.ADDRESS_TYPE, adr.POSTAL_CODE, adr.BASE_ADDRESS, adr.DETAIL_ADDRESS).from(adr)
            .where(adr.REGISTRATION_ID.eq(registrationId).and(adr.EFFECTIVE_TO.isNull))
            .orderBy(DSL.field("FIELD({0}, 'REGISTERED', 'ACTUAL')", Int::class.javaObjectType, adr.ADDRESS_TYPE))
            .fetch { BusinessDetailView.Address(it.value1(), it.value2(), it.value3(), it.value4()) }

        val con = BUSINESS_CONTACT
        val contacts = dsl.select(con.NAME, con.EMAIL, con.PHONE_NUMBER, con.IS_PRIMARY).from(con)
            .where(con.BUSINESS_ID.eq(id).and(con.EFFECTIVE_TO.isNull))
            .orderBy(con.DISPLAY_ORDER)
            .fetch { BusinessDetailView.Contact(it.value1(), it.value2(), it.value3(), it.value4().toInt() == 1) }

        return BusinessDetailView(business, registration, representatives, categories, addresses, contacts)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun summary(businessId: BusinessId): BusinessSummary? =
        dsl.select(BUSINESS.BUSINESS_ID, BUSINESS.DISPLAY_NAME, MasterSqlText.registrationNumber(), BUSINESS.REGISTRATION_STATUS)
            .from(BUSINESS)
            .where(BUSINESS.BUSINESS_ID.eq(businessId.value))
            .fetchOne { BusinessSummary(it.value1(), it.value2(), it.value3(), it.value4()) }
}
