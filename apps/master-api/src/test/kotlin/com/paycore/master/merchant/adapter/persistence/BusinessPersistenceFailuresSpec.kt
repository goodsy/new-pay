package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.StoreConstraint
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.SQLIntegrityConstraintViolationException

/**
 * BUSINESS-PROFILE-001 BIZ-1-5(§5.2 끝 — M-4·C-34): V15 하위 표 현재 행 UNIQUE 7개 → `BUSINESS_PROFILE_ROW`(businessKey = false — 결함, 재시도·재판정 없음).
 * 사업자등록번호 UNIQUE만 업무 키(기존 — 대조). 변이 (j)(새 UNIQUE를 businessKey = true로) → RED.
 */
class BusinessPersistenceFailuresSpec : BehaviorSpec({

    fun duplicate(key: String) = SQLIntegrityConstraintViolationException("Duplicate entry 'biz_01JCZ8V3N6Q2R4T7W9XB5D1F3H#1' for key '$key'", "23000", 1062)

    Given("BIZ-1-5 MySQL 1062 on the business profile current-row keys") {
        Then("all seven V15 key names are the BUSINESS_PROFILE_ROW defect class (not a business key); the value never appears") {
            val keys = listOf(
                "business_registration.uk_breg_business", "business_representative.uk_brep_current_order", "business_category.uk_bcat_current_order",
                "business_category.uk_bcat_current_name", "business_address.uk_badr_current_type", "business_contact.uk_bcon_current_order",
                "business_contact.uk_bcon_current_primary",
            )
            keys.associateWith { key ->
                val failure = PersistenceFailures.classify("business", RuntimeException("flush", duplicate(key))).shouldBeInstanceOf<PersistenceFailure.UniqueConflict>()
                (failure.message ?: "").contains("biz_01JCZ8") shouldBe false
                failure.constraint to failure.constraint.businessKey
            } shouldBe keys.associateWith { StoreConstraint.BUSINESS_PROFILE_ROW to false }
        }

        Then("control: the registration number key stays a business key (new-TX re-decision → stored DUPLICATE)") {
            PersistenceFailures.constraintOf("Duplicate entry 'x' for key 'business.uk_business_registration_number'").let { it to it.businessKey } shouldBe
                (StoreConstraint.BUSINESS_REGISTRATION_NUMBER to true)
        }
    }
})
