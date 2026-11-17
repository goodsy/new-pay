package com.paycore.admin.approval.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * MERCHANT-CODE-001 MC-3a §5.6.4 ref/key 분리(C-31·C-36, rev 0.4.7 m-5) — MC-3-10 표 시험(Domain 절반).
 * `ApprovalTarget.Merchant(merchantId, code)`: 표기 `ref` = 코드(없으면 내부 키), 내부 키 `merchantKey` = merchantId. 코드는 §3.1 형식만(`require`), 새 요청 팩토리는 코드 non-null,
 * 저장 행 재구성은 `target_merchant_id`(새 행)·`target_ref`(MC-3a 전 행 — mer_ 또는 코드)에서 같은 가맹점 하나를 만든다.
 */
class MerchantTargetCodeSpec : BehaviorSpec({

    val merchant = "mer_01JCME00000000000000000001"

    Given("a merchant approval target with a merchant code (MC-3a)") {
        Then("ref = code, merchantKey = merchantId; without a code (pre-MC-3a row) ref = merchantId") {
            val coded = ApprovalTarget.Merchant.forNewRequest(merchant, "shopabcd12")
            val legacy = ApprovalTarget.Merchant(merchant)
            listOf(coded.ref, coded.merchantKey, coded.code, legacy.ref, legacy.merchantKey, legacy.code) shouldBe
                listOf("shopabcd12", merchant, "shopabcd12", merchant, merchant, null)
        }
        Then("the code follows §3.1 (6..14, lower-case letter first, letters and digits only) — upper case, '_', 5 and 15 characters are refused") {
            listOf("a00000", "a0000000000000", "demoshop01").map { ApprovalTarget.Merchant(merchant, it).ref } shouldBe listOf("a00000", "a0000000000000", "demoshop01")
            listOf("ShopAbcd12", "shop_abcd12", "shop1", "a00000000000000", "1shopabcd", "", " shopabcd12", merchant).forEach { bad ->
                withClue(bad) { shouldThrow<IllegalArgumentException> { ApprovalTarget.Merchant(merchant, bad) } }
            }
        }
        Then("reconstitution: new rows (target_merchant_id + code target_ref), MC-1b rows (both mer_), pre-MC-1b rows (mer_ target_ref only) are the same merchant") {
            listOf(
                ApprovalTarget.Merchant.reconstitute(merchant, "shopabcd12"),
                ApprovalTarget.Merchant.reconstitute(merchant, merchant),
                ApprovalTarget.Merchant.reconstitute(null, merchant),
            ).map { listOf(it.merchantKey, it.ref, it.code) } shouldBe listOf(
                listOf(merchant, "shopabcd12", "shopabcd12"), listOf(merchant, merchant, null), listOf(merchant, merchant, null),
            )
            // 코드 표기인데 내부 키가 없는 행(V13 ③이 막는 모양)은 재구성 실패(fail-closed — 코드로 가맹점을 해석하지 않는다, C-30).
            shouldThrow<IllegalArgumentException> { ApprovalTarget.Merchant.reconstitute(null, "shopabcd12") }
        }
        Then("target identity for state decisions is the merchant key (is-check + merchantKey), not data-class equality including the code") {
            val before = ApprovalTarget.Merchant(merchant)
            val after = ApprovalTarget.Merchant(merchant, "shopabcd12")
            listOf(before == after, before.merchantKey == after.merchantKey) shouldBe listOf(false, true)
        }
    }
})
