package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.CapturedLogs
import com.paycore.master.merchant.application.provided.MerchantLabel
import com.paycore.master.merchant.application.provided.MerchantLabelQuery
import com.paycore.master.merchant.domain.MerchantId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.mock.web.MockHttpServletRequest

/**
 * MERCHANT-CODE-001 MC-3-17(MASTER 컨트롤러 직접, 대역 Query): 저장소 실패·코드 없는 가맹점(결함) → 503 `TEMPORARILY_UNAVAILABLE`(예외 문구는 로그에 없다), 형식 위반 본문은 Query를
 * 부르지 않는 400, 라벨 permit 소진은 Query를 부르지 않는 503 `ADMIN_READ_BUSY` + `busy{endpoint="merchant_labels"}`(MC-3-14 — 라벨 permit은 거래 목록 라벨과 **공유**,
 * 조회 permit과 별개). Query에는 형식 검사된 값 객체만, 요청 순서와 무관하게 정렬해 넘긴다.
 */
class MerchantLabelControllerSpec : BehaviorSpec({

    val mer1 = "mer_01JC0000000000000000000001"
    val mer2 = "mer_01JC0000000000000000000002"
    val canary = "SELECT display_name FROM merchant WHERE merchant_id = 'LEAKCANARY'"

    class Fixture(val failure: RuntimeException? = null, labelPermits: Int = 2, readPermits: Int = 4) {
        val registry = SimpleMeterRegistry()
        val permits = AdminChannelPermits(MasterAdminChannelFixture.settings(readPermits = readPermits, labelPermits = labelPermits), registry)
        val calls = mutableListOf<List<String>>()
        val controller = MerchantLabelController(
            object : MerchantLabelQuery {
                override fun labels(merchantIds: List<MerchantId>): List<MerchantLabel> {
                    calls += merchantIds.map { it.value }
                    failure?.let { throw it }
                    return listOf(MerchantLabel(merchantIds.first().value, "데모상점", "demoshop01"))
                }
            },
            permits,
        )

        fun post(body: String) = controller.labels(MockHttpServletRequest("POST", "/internal/v1/admin/merchant-labels").apply { setContent(body.toByteArray()) })
    }

    Given("MC-3-17 the merchant label controller with a stand-in query") {
        Then("a well-formed body reaches the query with the ids sorted; the answer is the items list of exactly three keys") {
            val f = Fixture()
            val res = f.post("""{"merchantIds":["$mer2","$mer1"]}""")
            res.statusCode.value() shouldBe 200
            String(res.body!!, Charsets.UTF_8) shouldBe """{"items":[{"merchantId":"$mer1","merchantName":"데모상점","merchantCode":"demoshop01"}]}"""
            f.calls shouldBe listOf(listOf(mer1, mer2))
        }

        Then("a storage failure or a merchant without a code is 503 TEMPORARILY_UNAVAILABLE and the exception text never reaches the logs") {
            listOf(IllegalStateException(canary), IllegalStateException("merchant without a code assignment $canary")).forEach { failure ->
                val f = Fixture(failure)
                val lines = CapturedLogs().use { logs ->
                    val res = f.post("""{"merchantIds":["$mer1"]}""")
                    res.statusCode.value() shouldBe 503
                    String(res.body!!, Charsets.UTF_8) shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}"""
                    logs.lines()
                }
                lines.forEach { it shouldNotContain "LEAKCANARY" }
                lines.any { "operation=MerchantLabels" in it && "TEMPORARILY_UNAVAILABLE" in it } shouldBe true
            }
        }

        Then("a malformed body (duplicate ids, a MID instead of a merchant key) is 400 without calling the query") {
            val f = Fixture()
            f.post("""{"merchantIds":["$mer1","$mer1"]}""").statusCode.value() shouldBe 400
            f.post("""{"merchantIds":["mid_01JC0000000000000000000001"]}""").statusCode.value() shouldBe 400
            f.calls shouldBe emptyList()
        }

        Then("MC-3-14 with the shared label permit held the call is 503 ADMIN_READ_BUSY at once without calling the query, counted under merchant_labels; the read permit is untouched") {
            val f = Fixture(labelPermits = 1)
            // 거래 목록 라벨(merchant-account-labels)이 permit을 잡고 있는 상황 — 같은 Semaphore를 나눠 쓴다(C-34).
            val inner = f.permits.labels("merchant_account_labels", { error("permit expected") }) { f.post("""{"merchantIds":["$mer1"]}""") }
            inner.statusCode.value() shouldBe 503
            String(inner.body!!, Charsets.UTF_8) shouldBe """{"error":"ADMIN_READ_BUSY"}"""
            f.calls shouldBe emptyList()
            f.registry.counter(AdminChannelPermits.BUSY_METRIC, "endpoint", "merchant_labels").count() shouldBe 1.0
            // 역방향: 조회 permit을 모두 잡아도 라벨은 답한다.
            val g = Fixture(labelPermits = 1, readPermits = 1)
            g.permits.read("detail", { error("permit expected") }) { g.post("""{"merchantIds":["$mer1"]}""") }.statusCode.value() shouldBe 200
        }
    }
})
