package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.application.provided.AdminPaymentCursor
import com.paycore.payment.payment.application.provided.AdminPaymentDetail
import com.paycore.payment.payment.application.provided.AdminPaymentListItem
import com.paycore.payment.payment.application.provided.AdminPaymentPage
import com.paycore.payment.payment.application.provided.AdminPaymentQuery
import com.paycore.payment.payment.application.provided.AdminPaymentSearchCriteria
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * TXN-LIST-ENRICH-001 E-1 T-E1-4(N-1 — 교차 배포 golden, PAYMENT 쪽): 고정 Demo 값 목록 쪽을 실제 진입 컨트롤러 [AdminPaymentController]로 만든 응답 바이트를 다시 읽어 압축 직렬화한
 * 문자열이 golden `admin-payment-list-v1.golden.json`의 `list`와 같다(키 순서·값). ADMIN `PaymentListGoldenSpec`이 같은 파일 사본(바이트 동일 단언)을 `PaymentResponseShape.LIST`로 해석한다.
 */
class AdminPaymentListGoldenSpec : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val golden: JsonNode = mapper.readTree(AdminPaymentListGoldenSpec::class.java.getResource("/contracts/admin-payment-list-v1.golden.json")!!.readText(Charsets.UTF_8))
    val page = AdminPaymentPage(
        listOf(
            AdminPaymentListItem(
                "pay_01JC0000000000000000000002", "LIVE", "mid_01JC0000000000000000000001", "order-0002", "가나다라마바사아자차", true, 10_000, "KRW", "CARD", "AUTHORIZED",
                Instant.parse("2026-11-01T01:02:03.456Z"),
            ),
            AdminPaymentListItem(
                "pay_01JC0000000000000000000001", "TEST", "mid_01JC0000000000000000000002", "order-0001", null, false, 2_500, "KRW", "CARD", "FAILED",
                Instant.parse("2026-11-01T01:00:00Z"),
            ),
        ),
        AdminPaymentCursor(Instant.parse("2026-11-01T01:00:00Z"), "pay_01JC0000000000000000000001"),
    )
    val registry = SimpleMeterRegistry()
    val controller = AdminPaymentController(
        object : AdminPaymentQuery {
            override fun search(criteria: AdminPaymentSearchCriteria): AdminPaymentPage = page

            override fun detail(paymentId: String): AdminPaymentDetail? = null
        },
        AdminChannelPermits(AdminOpsSettings(readPermits = 1, commandPermits = 1, publicPoolReserve = 1), registry), registry,
    )

    Given("T-E1-4 a fixed Demo page serialized by the PAYMENT admin search controller") {
        Then("T-E1-4 11 item keys (paymentMethod after currency) — compact JSON equals the golden list") {
            val response = controller.search(
                MockHttpServletRequest("POST", "/internal/v1/admin/payment-searches").apply {
                    setContent("""{"createdFrom":"2026-11-01T00:00:00.000Z","createdTo":"2026-11-02T00:00:00.000Z"}""".toByteArray())
                },
            )
            response.statusCode.value() shouldBe 200
            val produced = mapper.readTree(response.body!!)
            mapper.writeValueAsString(produced) shouldBe mapper.writeValueAsString(golden.get("list"))
            produced.get("items").get(0).propertyNames().toList() shouldBe listOf(
                "paymentId", "environment", "merchantAccountId", "merchantOrderId", "orderNamePreview", "orderNameTruncated", "amountMinor", "currency", "paymentMethod",
                "status", "createdAt",
            )
        }
    }
})
