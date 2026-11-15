package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.domain.MerchantCodeChangeCommandBody
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * MERCHANT-CODE-001 §8 MC-1b-2(ADMIN 절반): 코드 변경 본문·`mop1` 지문. 아래 hex 두 개는 Python `hashlib.sha256`으로 따로 계산한 독립 벡터로 MASTER `MerchantCodeChangeParserSpec`과
 * **같은 입력·같은 hex**다(코드 공유 없는 두 앱 계약 — acd1 선례). 본문 필드 순서·등록자 필드 부재도 고정한다.
 */
class MerchantCodeChangeCommandBodySpec : BehaviorSpec({

    fun body(newCode: String = "shopabcd99", reason: String = "TYPO_CORRECTION", merchantId: String = "mer_01JCMC00000000000000000MC1") = MerchantCodeChangeCommandBody(
        "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H", "admin:adm_01JCRQ0000000000000000REQ1", "admin:adm_01JCAP0000000000000000APR1",
        Instant.parse("2026-11-10T01:00:00.000Z"), Instant.parse("2026-11-10T02:00:00.000Z"), "MERCHANT-REQ/2026-0001", merchantId, "shopabcd12", newCode, reason,
    )

    Given("the independent mop1 vectors shared with MASTER") {
        Then("two vectors hash to the same fixed hex as MASTER") {
            body().fingerprint() shouldBe "mop1:b4c41826428235679caa5ba9fd1b5e83ce639a89f68f7be6d331c37edd2854b5"
            body("newshop2026", "MERCHANT_REQUEST").fingerprint() shouldBe "mop1:94ad9053551bfac86df334ad8072b6d9ef6977651a108d32e4c05b247cfd591a"
        }
        Then("a one-character change gives another fingerprint") {
            body("shopabcd98").fingerprint() shouldNotBe body().fingerprint()
            body(merchantId = "mer_01JCMC00000000000000000MC2").fingerprint() shouldNotBe body().fingerprint()
        }
    }

    Given("the JSON body") {
        Then("field order = evidence 9 + operation + merchantId, currentCode, newCode, reasonCode; no registrant / actor field; toString has no evidence reference") {
            val node = JsonMapper.builder().build().readTree(body().json())
            node.propertyNames().toList() shouldBe listOf(
                "idempotencyKey", "correlationId", "approvalId", "requestedBy", "approvedBy", "approvedAt", "executeBefore", "evidenceRef", "approvalFingerprint", "operation",
                "merchantId", "currentCode", "newCode", "reasonCode",
            )
            listOf("operation", "currentCode", "newCode", "reasonCode", "approvalFingerprint").map { node.get(it).asString() } shouldBe listOf(
                "ChangeMerchantCode", "shopabcd12", "shopabcd99", "TYPO_CORRECTION", body().fingerprint(),
            )
            body().toString() shouldNotContain "MERCHANT-REQ"
        }
        Then("invalid values are refused before MASTER (uppercase code, same code, unknown reason, non-mer_ id)") {
            listOf(
                { body("ShopAbcd99") }, { body("shopabcd12") }, { body(reason = "CHANGED_MIND") }, { body(merchantId = "biz_01JCMC00000000000000000MC1") },
            ).forEach { shouldThrow<IllegalArgumentException> { it() } }
        }
    }
})
