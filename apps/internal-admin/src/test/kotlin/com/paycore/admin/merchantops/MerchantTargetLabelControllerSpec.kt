package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.adapter.web.MerchantTargetLabelController
import com.paycore.admin.merchantops.application.provided.MerchantLabel
import com.paycore.admin.merchantops.application.provided.MerchantLabelFailure
import com.paycore.admin.merchantops.application.provided.MerchantTargetLabelResult
import com.paycore.admin.merchantops.application.provided.MerchantTargetLabelUseCase
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.TestingAuthenticationToken
import tools.jackson.databind.json.JsonMapper

/**
 * MERCHANT-CODE-001 MC-3-19 화면 라벨 API 응답 매핑(컨트롤러 직접, 대역 Use Case): 라벨 분당 창 초과 → **429 `RATE_LIMITED`**(화면은 UNAVAILABLE과 같게 다룬다 — C-35),
 * UNAVAILABLE(모든 분류) → **HTTP 200** `{"items":[],"labels":"UNAVAILABLE"}`, Found → items merchantId 오름차순 3키(`merchantId`·`name`·`code`) + `"labels":"OK"`,
 * 403·400 매핑, Use Case에는 actor와 구조 해석된 키 목록(해석 실패면 null)만 간다. 모든 응답 no-store.
 */
class MerchantTargetLabelControllerSpec : BehaviorSpec({

    val json = JsonMapper.builder().build()
    val mer1 = "mer_01JC0000000000000000000001"
    val mer2 = "mer_01JC0000000000000000000002"

    class Fixture(val reply: MerchantTargetLabelResult) {
        val calls = mutableListOf<Pair<String, List<String>?>>()
        val controller = MerchantTargetLabelController(
            object : MerchantTargetLabelUseCase {
                override fun labels(viewerActorId: String, merchantIds: List<String>?): MerchantTargetLabelResult = reply.also { calls += viewerActorId to merchantIds }
            },
        )

        fun post(body: String) = controller.labels(
            TestingAuthenticationToken("adm_01JC00000000000000000000V1", null),
            MockHttpServletRequest("POST", "/api/merchant-labels").apply {
                contentType = "application/json"
                setContent(body.toByteArray())
            },
        )
    }

    Given("MC-3-19 response mapping") {
        Then("RateLimited is 429 RATE_LIMITED no-store") {
            val res = Fixture(MerchantTargetLabelResult.RateLimited).post("""{"merchantIds":["$mer1"]}""")
            res.statusCode.value() shouldBe 429
            json.writeValueAsString(res.body) shouldBe """{"error":"RATE_LIMITED"}"""
            res.headers.cacheControl shouldBe "no-store"
        }

        Then("Unavailable (BUSY, UNAVAILABLE, UNREADABLE) is HTTP 200 with labels UNAVAILABLE and no items") {
            MerchantLabelFailure.entries.forEach { failure ->
                val res = Fixture(MerchantTargetLabelResult.Unavailable(failure)).post("""{"merchantIds":["$mer1"]}""")
                res.statusCode.value() shouldBe 200
                json.writeValueAsString(res.body) shouldBe """{"items":[],"labels":"UNAVAILABLE"}"""
                res.headers.cacheControl shouldBe "no-store"
            }
        }

        Then("Found lists items sorted by merchantId with exactly merchantId, name, code and labels OK; the use case got the actor and the parsed ids") {
            val f = Fixture(MerchantTargetLabelResult.Found(mapOf(mer2 to MerchantLabel("B상점", "shopbbbb02"), mer1 to MerchantLabel("데모상점", "demoshop01"))))
            val res = f.post("""{"merchantIds":["$mer2","$mer1"]}""")
            res.statusCode.value() shouldBe 200
            json.writeValueAsString(res.body) shouldBe
                """{"items":[{"merchantId":"$mer1","name":"데모상점","code":"demoshop01"},{"merchantId":"$mer2","name":"B상점","code":"shopbbbb02"}],"labels":"OK"}"""
            f.calls shouldBe listOf("adm_01JC00000000000000000000V1" to listOf(mer2, mer1))
        }

        Then("Forbidden is 403, InvalidInput is 400; a structurally malformed body reaches the use case as null (the role is judged first)") {
            Fixture(MerchantTargetLabelResult.Forbidden).post("{}").statusCode.value() shouldBe 403
            val f = Fixture(MerchantTargetLabelResult.InvalidInput)
            val res = f.post("""{"merchantIds":["$mer1"],"x":1}""")
            res.statusCode.value() shouldBe 400
            json.writeValueAsString(res.body) shouldBe """{"error":"INVALID_INPUT"}"""
            f.calls shouldBe listOf("adm_01JC00000000000000000000V1" to null)
        }
    }
})
