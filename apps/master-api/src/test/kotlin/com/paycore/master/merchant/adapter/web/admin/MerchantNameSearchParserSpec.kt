package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.domain.Lifecycle
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * MERCHANT-NAME-SEARCH-001 MNS-1 parser 부분: 가맹점 검색 본문 `displayNamePrefix`(가맹점명 앞부분 — 사업자 검색 `displayNamePrefix`와 같은 해석, [com.paycore.master.merchant.domain.DisplayName]
 * 규칙 1~100 code point·앞뒤 공백·제어문자 거절, 정규화 없음). `merchantCode`(정확 일치 단일 기준)와 함께면 400, 다른 필터·cursor·limit과는 함께 쓴다.
 */
class MerchantNameSearchParserSpec : BehaviorSpec({

    val biz = "biz_01JC0000000000000000000001"
    val mer = "mer_01JC0000000000000000000001"
    fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    Given("MNS-1 the merchant search body with displayNamePrefix") {
        Then("MNS-1-P1 a well-formed prefix comes through unchanged (no trim, no case folding; %, _ and ! are plain characters)") {
            // 리뷰 NIT-3: 100 code point 경계는 서로게이트 쌍(😀 = UTF-16 200 char)으로도 통과(101은 P2에서 400). DisplayName 단위 시험(MasterValuesSpec:43)은 "가" 100만 본다.
            listOf("데모", "Demo Mer", "50%_!", "😀가게", "a".repeat(100), "😀".repeat(100)).forEach { prefix ->
                withClue(prefix) {
                    MerchantSearchRequestParser.parse(bytes("""{"displayNamePrefix":"$prefix"}""")).shouldNotBeNull().displayNamePrefix!!.value shouldBe prefix
                }
            }
        }

        Then("MNS-1-P2 empty, leading/trailing space, control character, 101 code points, a non-string or null prefix is 400 (null)") {
            listOf(
                """{"displayNamePrefix":""}""",
                """{"displayNamePrefix":" 데모"}""",
                """{"displayNamePrefix":"데모 "}""",
                """{"displayNamePrefix":"a\nb"}""",
                """{"displayNamePrefix":"a\u0000b"}""",
                """{"displayNamePrefix":"${"a".repeat(101)}"}""",
                """{"displayNamePrefix":"${"😀".repeat(101)}"}""",
                """{"displayNamePrefix":12345}""",
                """{"displayNamePrefix":null}""",
                """{"displayNamePrefix":["데모"]}""",
            ).forEach { body -> withClue(body) { MerchantSearchRequestParser.parse(bytes(body)).shouldBeNull() } }
        }

        Then("MNS-1-P3 displayNamePrefix with merchantCode is 400 (merchantCode stays a single criterion)") {
            listOf(
                """{"merchantCode":"shopabcd12","displayNamePrefix":"데모"}""",
                """{"displayNamePrefix":"데모","merchantCode":"shopabcd12","limit":1}""",
            ).forEach { body -> withClue(body) { MerchantSearchRequestParser.parse(bytes(body)).shouldBeNull() } }
        }

        Then("MNS-1-P4 displayNamePrefix combines with lifecycle, businessId, registrationNumber, merchantId, after and limit") {
            val f = MerchantSearchRequestParser.parse(
                bytes(
                    """{"displayNamePrefix":"데모","lifecycle":"ACTIVE","businessId":"$biz","registrationNumber":"7781234560",""" +
                        """"merchantId":"$mer","after":"$mer","limit":5}""",
                ),
            ).shouldNotBeNull()
            f.displayNamePrefix!!.value shouldBe "데모"
            f.lifecycle shouldBe Lifecycle.ACTIVE
            f.businessId!!.value shouldBe biz
            f.after!!.value shouldBe mer
            f.limit shouldBe 5
            f.merchantCode.shouldBeNull()
        }

        Then("MNS-1-P5 control: a body without displayNamePrefix has no name criterion") {
            MerchantSearchRequestParser.parse(bytes("""{"businessId":"$biz","limit":1}""")).shouldNotBeNull().displayNamePrefix.shouldBeNull()
        }
    }
})
