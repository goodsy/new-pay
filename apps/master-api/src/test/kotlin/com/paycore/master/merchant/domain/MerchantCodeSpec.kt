package com.paycore.master.merchant.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * MERCHANT-CODE-001 MC-1-1(§3.1·§3.2·§3.3): 가맹점 코드 값 객체 형식(정규화 없음)·예약어(완전·접두, Demo seed 예외), `Merchant.register`가 seq 1 `REGISTRATION` 배정 하나를 만들고
 * `currentCode` = 가장 큰 seq. 기대값은 구현 상수와 독립 리터럴.
 */
class MerchantCodeSpec : BehaviorSpec({

    Given("MC-1-1 the merchant code format ^[a-z][a-z0-9]{5,13}$ (C-1)") {
        Then("MC-1-1 well-formed codes and the 6/14-character boundaries are accepted as entered") {
            listOf(MerchantCodeFixtures.VALID, MerchantCodeFixtures.MIN_LENGTH, MerchantCodeFixtures.MAX_LENGTH, "a00000", "z9y8x7w6v5u4t3").forEach {
                MerchantCode(it).value shouldBe it
            }
        }

        Then("MC-1-1 5 and 15 characters, a leading digit, upper case, spaces, hyphens and underscores are rejected without normalization or echo") {
            listOf(
                "abcde", "a" + "b".repeat(14), "1abcdef", "Shopabcd12", "SHOPABCD12", "shopABCD12", " shopabcd12", "shopabcd12 ", "shop abcd12",
                "shop-abcd12", "shop_abcd12", "", "shöpabcd12", "ｓhopabcd12", "shopabcd12\n",
            ).forEach { raw ->
                val error = shouldThrow<IllegalArgumentException> { MerchantCode(raw) }
                if (raw.isNotBlank()) error.message!! shouldNotContain raw
            }
        }
    }

    Given("MC-1-1 reserved words (C-2 — exact and prefix match, Demo seed code exempt)") {
        Then("MC-1-1 every reserved word and any code starting with one is MERCHANT_CODE_RESERVED") {
            listOf(
                "paycore", "paycore01", "admin1", "adminshop", "system", "systems", "test01", "testshop", "live01", "liveshop", "demo01",
                "demoshop", "demoshop02", "api123", "apiary", "root01", "rootbeer", "null00", "nullable", "undefined", "undefined01",
            ).forEach { raw ->
                shouldThrow<MasterRuleViolation> { MerchantCode(raw).requireAssignable() }.rejection shouldBe MasterRejection.MERCHANT_CODE_RESERVED
            }
        }

        Then("MC-1-1 control: codes that only contain a reserved word elsewhere, and the Demo seed code demoshop01, are assignable") {
            listOf("mypaycore", "shopadmin", "atest1", "xdemo01", "capital", "shopabcd12", "demoshop01").forEach { MerchantCode(it).requireAssignable() }
        }
    }

    Given("MC-1-1 Merchant.register with a code (§3.3)") {
        val business = MasterFixture.business()

        Then("MC-1-1 registration creates exactly one seq-1 REGISTRATION assignment and it is the current code") {
            val merchant = Merchant.register(MasterFixture.merchantId(), business, DisplayName("Demo Merchant"), MerchantCode("shopabcd12"), MasterFixture.t0, MasterFixture.operator)
            merchant.codeAssignments shouldHaveSize 1
            val only = merchant.codeAssignments.single()
            listOf(only.seq, only.code.value, only.source, only.replacesCode, only.approvalId, only.reasonCode, only.assignedAt, only.assignedBy) shouldBe
                listOf(1, "shopabcd12", MerchantCodeSource.REGISTRATION, null, null, null, MasterFixture.t0, MasterFixture.operator)
            merchant.currentCode.value shouldBe "shopabcd12"
        }

        Then("MC-1-1 a reserved code is refused by the domain (MERCHANT_CODE_RESERVED)") {
            shouldThrow<MasterRuleViolation> {
                Merchant.register(MasterFixture.merchantId(), business, DisplayName("Demo Merchant"), MerchantCode("adminshop"), MasterFixture.t0, MasterFixture.operator)
            }.rejection shouldBe MasterRejection.MERCHANT_CODE_RESERVED
        }

        Then("MC-1-1 currentCode is the assignment with the highest seq (a stored seq-2 change row)") {
            val first = MerchantCodeAssignment.registration(MerchantCode("shopabcd12"), MasterFixture.t0, MasterFixture.operator)
            val change = MerchantCodeAssignment.reconstitute(
                2, MerchantCode("shopxyz789"), MerchantCode("shopabcd12"), MerchantCodeSource.CHANGE, "apr_01HZY3B0Q7KJ8M9N4P5R6S7T8V", "TYPO_CORRECTION",
                MasterFixture.t0, ActorRef("admin:adm_01HZY3B0Q7KJ8M9N4P5R6S7T8V"),
            )
            val stored = Merchant.reconstitute(
                MasterFixture.merchantId(), business.id, DisplayName("Demo Merchant"), Lifecycle.ACTIVE, 0, MasterFixture.t0, MasterFixture.operator, listOf(first, change),
            )
            stored.currentCode.value shouldBe "shopxyz789"
        }

        Then("MC-1-1 a stored merchant without a seq-1 assignment, or with a seq-2 row that does not replace seq 1, is a defect signal (IllegalState — §3.2)") {
            shouldThrow<IllegalStateException> {
                Merchant.reconstitute(MasterFixture.merchantId(), business.id, DisplayName("Demo Merchant"), Lifecycle.ACTIVE, 0, MasterFixture.t0, MasterFixture.operator, emptyList())
            }
            val first = MerchantCodeAssignment.registration(MerchantCode("shopabcd12"), MasterFixture.t0, MasterFixture.operator)
            val foreign = MerchantCodeAssignment.reconstitute(
                2, MerchantCode("shopxyz789"), MerchantCode("othercode1"), MerchantCodeSource.CHANGE, "apr_01HZY3B0Q7KJ8M9N4P5R6S7T8V", "TYPO_CORRECTION",
                MasterFixture.t0, MasterFixture.operator,
            )
            shouldThrow<IllegalStateException> {
                Merchant.reconstitute(MasterFixture.merchantId(), business.id, DisplayName("Demo Merchant"), Lifecycle.ACTIVE, 0, MasterFixture.t0, MasterFixture.operator, listOf(first, foreign))
            }
        }

        Then("MC-1-1 assignment row invariants mirror the V13 CHECK (seq 1 has no change fields; seq 2 has all of them and differs from what it replaces)") {
            shouldThrow<IllegalArgumentException> {
                MerchantCodeAssignment.reconstitute(1, MerchantCode("shopabcd12"), MerchantCode("shopxyz789"), MerchantCodeSource.REGISTRATION, null, null, MasterFixture.t0, MasterFixture.operator)
            }
            shouldThrow<IllegalArgumentException> {
                MerchantCodeAssignment.reconstitute(1, MerchantCode("shopabcd12"), null, MerchantCodeSource.CHANGE, null, null, MasterFixture.t0, MasterFixture.operator)
            }
            shouldThrow<IllegalArgumentException> {
                MerchantCodeAssignment.reconstitute(2, MerchantCode("shopabcd12"), MerchantCode("shopabcd12"), MerchantCodeSource.CHANGE, "apr_01HZY3B0Q7KJ8M9N4P5R6S7T8V", "TYPO_CORRECTION", MasterFixture.t0, MasterFixture.operator)
            }
            shouldThrow<IllegalArgumentException> {
                MerchantCodeAssignment.reconstitute(3, MerchantCode("shopabcd12"), null, MerchantCodeSource.BACKFILL, null, null, MasterFixture.t0, MasterFixture.operator)
            }
            MerchantCodeAssignment.reconstitute(1, MerchantCode("m0123456789abc"), null, MerchantCodeSource.BACKFILL, null, null, MasterFixture.t0, ActorRef("system:migration")).source shouldBe
                MerchantCodeSource.BACKFILL
        }
    }
})
