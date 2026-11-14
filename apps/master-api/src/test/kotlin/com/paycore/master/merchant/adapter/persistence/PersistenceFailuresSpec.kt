package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.StoreConstraint
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.SQLIntegrityConstraintViolationException

/**
 * MERCHANT-CODE-001 MC-1-11(§3.2 1062 분류 표, C-25): V13 배정 표의 UNIQUE/PK 키 이름 → [StoreConstraint]·businessKey. 키 이름이 표에 없으면 `OTHER`(대조).
 * 분류 뒤 실행기 재판정 결과(TAKEN)는 MySQL 경합 시험(MerchantCodeRegistrationSpec MC-1-3)이 확인한다.
 */
class PersistenceFailuresSpec : BehaviorSpec({

    fun duplicate(key: String) = SQLIntegrityConstraintViolationException("Duplicate entry 'shopabcd12' for key '$key'", "23000", 1062)

    Given("MC-1-11 MySQL 1062 on the merchant_code_assignment keys") {
        Then("MC-1-11 each V13 key name maps to its StoreConstraint and every one is a business key (retried in a new TX)") {
            mapOf(
                "merchant_code_assignment.uk_mca_code" to StoreConstraint.MERCHANT_CODE,
                "merchant_code_assignment.uk_mca_merchant_code" to StoreConstraint.MERCHANT_CODE,
                "merchant_code_assignment.PRIMARY" to StoreConstraint.MERCHANT_CODE_SEQ,
                "merchant_code_assignment.uk_mca_approval" to StoreConstraint.MERCHANT_CODE_APPROVAL,
            ).forEach { (key, expected) ->
                val failure = PersistenceFailures.classify("merchant", RuntimeException("flush", duplicate(key))).shouldBeInstanceOf<PersistenceFailure.UniqueConflict>()
                (key to failure.constraint) shouldBe (key to expected)
                (key to failure.constraint.businessKey) shouldBe (key to true)
            }
        }

        Then("MC-1-11 control: an unmapped key of the same table is OTHER (not a business key) and the duplicate value never appears in the failure") {
            val failure = PersistenceFailures.classify("merchant", duplicate("merchant_code_assignment.uk_unknown")).shouldBeInstanceOf<PersistenceFailure.UniqueConflict>()
            failure.constraint shouldBe StoreConstraint.OTHER
            failure.constraint.businessKey shouldBe false
            (failure.message!!.contains("shopabcd12")) shouldBe false
        }
    }
})
