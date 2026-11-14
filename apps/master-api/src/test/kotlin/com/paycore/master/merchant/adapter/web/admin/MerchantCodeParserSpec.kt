package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.domain.MerchantCodeFixtures
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * MERCHANT-CODE-001 MC-1-2·MC-1-6 parser 부분(§3.3·§3.6·§3.8): 가맹점 등록 본문 `merchantCode` 필수(형식 위반·누락 400 — 정규화 없음), 검색 본문 `merchantCode`는 정확 일치 단일 기준
 * (다른 필터와 함께 오면 400, `limit`만 함께 허용 — 구현 결정), 형식 위반 400.
 */
class MerchantCodeParserSpec : BehaviorSpec({

    val biz = "biz_01JC0000000000000000000001"
    fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    Given("MC-1-2 the REGISTER_MERCHANT body with merchantCode (§3.3)") {
        Then("MC-1-2 a well-formed code comes through unchanged") {
            MerchantOperationsRequestParser.merchant(bytes(MerchantOperationsBodies.registerMerchant(biz, code = MerchantCodeFixtures.VALID)))
                .shouldNotBeNull().merchantCode.value shouldBe "shopabcd12"
        }

        Then("MC-1-2 a missing, null, non-string or malformed code is 400 (null) — no normalization of upper case, spaces, hyphens or underscores") {
            val good = MerchantOperationsBodies.registerMerchant(biz, code = MerchantCodeFixtures.VALID)
            listOf(
                good.replace(",\"merchantCode\":\"shopabcd12\"", ""),
                good.replace("\"shopabcd12\"", "null"),
                good.replace("\"shopabcd12\"", "12345678"),
                good.replace("shopabcd12", "SHOPABCD12"),
                good.replace("shopabcd12", "Shopabcd12"),
                good.replace("shopabcd12", " shopabcd12"),
                good.replace("shopabcd12", "shop-abcd12"),
                good.replace("shopabcd12", "shop_abcd12"),
                good.replace("shopabcd12", "abcde"),
                good.replace("shopabcd12", "a" + "b".repeat(14)),
                good.replace("shopabcd12", "1shopabcd"),
            ).forEach { body -> withClue(body) { MerchantOperationsRequestParser.merchant(bytes(body)).shouldBeNull() } }
        }

        Then("MC-1-2 a reserved code passes the parser (it is a stored domain rejection, not a 400)") {
            MerchantOperationsRequestParser.merchant(bytes(MerchantOperationsBodies.registerMerchant(biz, code = "adminshop"))).shouldNotBeNull()
        }
    }

    Given("MC-1-6 the merchant search body with merchantCode (§3.8)") {
        Then("MC-1-6 merchantCode alone (optionally with limit) is a code search") {
            MerchantSearchRequestParser.parse(bytes("""{"merchantCode":"shopabcd12"}""")).shouldNotBeNull().merchantCode!!.value shouldBe "shopabcd12"
            MerchantSearchRequestParser.parse(bytes("""{"merchantCode":"shopabcd12","limit":1}""")).shouldNotBeNull().merchantCode!!.value shouldBe "shopabcd12"
        }

        Then("MC-1-6 merchantCode together with any other filter or the cursor is 400 (single criterion)") {
            listOf(
                """{"merchantCode":"shopabcd12","merchantId":"mer_01JC0000000000000000000001"}""",
                """{"merchantCode":"shopabcd12","businessId":"$biz"}""",
                """{"merchantCode":"shopabcd12","lifecycle":"ACTIVE"}""",
                """{"merchantCode":"shopabcd12","registrationNumber":"7781234560"}""",
                """{"merchantCode":"shopabcd12","after":"mer_01JC0000000000000000000001"}""",
            ).forEach { body -> withClue(body) { MerchantSearchRequestParser.parse(bytes(body)).shouldBeNull() } }
        }

        Then("MC-1-6 upper case and other malformed codes are 400") {
            listOf("SHOPABCD12", "shop-abcd12", "abcde", " shopabcd12", "").forEach { code ->
                withClue(code) { MerchantSearchRequestParser.parse(bytes("""{"merchantCode":"$code"}""")).shouldBeNull() }
            }
            MerchantSearchRequestParser.parse(bytes("""{"merchantCode":12345678}""")).shouldBeNull()
        }

        Then("MC-1-6 control: existing searches without merchantCode are unchanged (no code criterion)") {
            MerchantSearchRequestParser.parse(bytes("""{"businessId":"$biz","limit":1}""")).shouldNotBeNull().merchantCode.shouldBeNull()
        }
    }
})
