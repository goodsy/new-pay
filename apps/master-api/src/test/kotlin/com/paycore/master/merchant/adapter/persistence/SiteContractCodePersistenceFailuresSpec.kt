package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.StoreConstraint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.SQLIntegrityConstraintViolationException

/**
 * SITE-CONTRACT-CODE-001 §3.3 1062 분류(C-3): V19 코드 표의 PK·코드 UNIQUE·(가맹점, 순번) UNIQUE → `SITE_CONTRACT_CODE`(businessKey = false — 잠금 아래에서는 결함,
 * 실행기 재시도·재판정 없이 503 + 결함 로그). 업무 거절로 바꾸지 않는다. 값 원문은 실패에 남지 않는다.
 */
class SiteContractCodePersistenceFailuresSpec : FunSpec({

    fun duplicate(key: String) = SQLIntegrityConstraintViolationException("Duplicate entry 'site-demoshop01-01' for key '$key'", "23000", 1062)

    test("SCODE-1 every V19 unique key maps to SITE_CONTRACT_CODE, a defect (not a business key); the duplicate value is not kept") {
        listOf(
            "merchant_site_code.PRIMARY", "merchant_site_code.uk_msc_code", "merchant_site_code.uk_msc_merchant_seq",
            "contract_code.PRIMARY", "contract_code.uk_cc_code", "contract_code.uk_cc_merchant_seq",
        ).forEach { key ->
            val failure = PersistenceFailures.classify("merchant_site_code", RuntimeException("flush", duplicate(key))).shouldBeInstanceOf<PersistenceFailure.UniqueConflict>()
            (key to failure.constraint) shouldBe (key to StoreConstraint.SITE_CONTRACT_CODE)
            (key to failure.constraint.businessKey) shouldBe (key to false)
            failure.message!!.contains("site-demoshop01") shouldBe false
        }
    }
})
