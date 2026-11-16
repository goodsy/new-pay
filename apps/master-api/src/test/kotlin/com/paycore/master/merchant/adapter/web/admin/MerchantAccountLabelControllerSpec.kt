package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.CapturedLogs
import com.paycore.master.merchant.application.provided.MerchantAccountLabel
import com.paycore.master.merchant.application.provided.MerchantAccountLabelQuery
import com.paycore.master.merchant.domain.MerchantAccountId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.mock.web.MockHttpServletRequest

/**
 * TXN-LIST-ENRICH-001 E-2 T-E2-4(컨트롤러 직접, 대역 Query): 저장소 실패·코드 없는 가맹점(결함 — Reader의 checkNotNull) → 503 `TEMPORARILY_UNAVAILABLE`(예외 문구 — 바인드 값일 수 있다 — 는
 * 로그에 없다), 형식 위반 본문은 Query를 부르지 않는 400, 라벨 permit 소진은 Query를 부르지 않는 503 `ADMIN_READ_BUSY`. Query에는 형식 검사된 MID 값 객체만, 요청 순서와 무관하게 정렬해 넘긴다.
 */
class MerchantAccountLabelControllerSpec : BehaviorSpec({

    val mid1 = "mid_01JC0000000000000000000001"
    val mid2 = "mid_01JC0000000000000000000002"
    val canary = "SELECT display_name FROM merchant WHERE merchant_id = 'LEAKCANARY'"

    class Fixture(val failure: RuntimeException? = null, labelPermits: Int = 2) {
        val registry = SimpleMeterRegistry()
        val permits = AdminChannelPermits(MasterAdminChannelFixture.settings(labelPermits = labelPermits), registry)
        val calls = mutableListOf<List<String>>()
        val controller = MerchantAccountLabelController(
            object : MerchantAccountLabelQuery {
                override fun labels(merchantAccountIds: List<MerchantAccountId>): List<MerchantAccountLabel> {
                    calls += merchantAccountIds.map { it.value }
                    failure?.let { throw it }
                    return listOf(MerchantAccountLabel(merchantAccountIds.first().value, "데모상점", "demoshop01"))
                }
            },
            permits,
        )

        fun post(body: String) = controller.labels(MockHttpServletRequest("POST", "/internal/v1/admin/merchant-account-labels").apply { setContent(body.toByteArray()) })
    }

    Given("T-E2-4 the label controller with a stand-in query") {
        Then("a well-formed body reaches the query with the ids sorted; the answer is the items list") {
            val f = Fixture()
            val res = f.post("""{"merchantAccountIds":["$mid2","$mid1"]}""")
            res.statusCode.value() shouldBe 200
            String(res.body!!, Charsets.UTF_8) shouldBe """{"items":[{"merchantAccountId":"$mid1","merchantName":"데모상점","merchantCode":"demoshop01"}]}"""
            f.calls shouldBe listOf(listOf(mid1, mid2))
        }

        Then("a storage failure or a merchant without a code is 503 TEMPORARILY_UNAVAILABLE and the exception text never reaches the logs") {
            listOf(IllegalStateException(canary), IllegalStateException("merchant without a code assignment $canary")).forEach { failure ->
                val f = Fixture(failure)
                val lines = CapturedLogs().use { logs ->
                    val res = f.post("""{"merchantAccountIds":["$mid1"]}""")
                    res.statusCode.value() shouldBe 503
                    String(res.body!!, Charsets.UTF_8) shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}"""
                    logs.lines()
                }
                lines.forEach { it shouldNotContain "LEAKCANARY" }
                lines.any { "operation=MerchantAccountLabels" in it && "TEMPORARILY_UNAVAILABLE" in it } shouldBe true
            }
        }

        Then("a malformed body is 400 without calling the query") {
            val f = Fixture()
            f.post("""{"merchantAccountIds":["$mid1","$mid1"]}""").statusCode.value() shouldBe 400
            f.calls shouldBe emptyList()
        }

        Then("with the label permit held the call is 503 ADMIN_READ_BUSY at once without calling the query, counted per endpoint; the read permit is untouched") {
            val f = Fixture(labelPermits = 1)
            val inner = f.permits.labels("hold", { error("permit expected") }) { f.post("""{"merchantAccountIds":["$mid1"]}""") }
            inner.statusCode.value() shouldBe 503
            String(inner.body!!, Charsets.UTF_8) shouldBe """{"error":"ADMIN_READ_BUSY"}"""
            f.calls shouldBe emptyList()
            f.registry.counter(AdminChannelPermits.BUSY_METRIC, "endpoint", "merchant_account_labels").count() shouldBe 1.0
            f.permits.labels("hold", { "busy" }) { f.permits.read("detail", { "busy" }) { "read-ok" } } shouldBe "read-ok"
            f.permits.read("detail", { "busy" }) { f.permits.labels("labels", { "busy" }) { "labels-ok" } } shouldBe "labels-ok"
        }
    }
})
