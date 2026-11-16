package com.paycore.admin.paymentops

import com.paycore.admin.paymentops.adapter.integration.PaymentResponseShape
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.io.File

/**
 * TXN-LIST-ENRICH-001 E-1 T-E1-3·T-E1-4(N-1 — 교차 배포 golden, ADMIN 쪽): PAYMENT `AdminPaymentListGoldenSpec`이 실제 진입 컨트롤러로 만든 응답과 같은 golden
 * `admin-payment-list-v1.golden.json`(이 모듈 사본 — PAYMENT 원본과 **바이트 동일** 단언)을 `PaymentResponseShape.LIST`로 해석한다(11키 통과, `paymentMethod` 값 보존).
 * 대조군(fail-closed): 10키(paymentMethod 없음 — 이전 PAYMENT), 모르는 키 추가, 형식 위반(소문자·빈 값·숫자) → 해석 실패(= 503 PAYMENT_UNAVAILABLE 경로).
 */
class PaymentListGoldenSpec : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val resource = PaymentListGoldenSpec::class.java.getResource("/contracts/admin-payment-list-v1.golden.json")!!
    val golden: JsonNode = mapper.readTree(resource.readText(Charsets.UTF_8))
    fun list(): ObjectNode = golden.get("list").deepCopy() as ObjectNode
    fun first(node: ObjectNode): ObjectNode = (node.get("items") as ArrayNode).get(0) as ObjectNode
    fun parses(node: JsonNode): Boolean = runCatching { PaymentResponseShape.LIST.read(node) }.isSuccess

    Given("T-E1-4 the cross-deployment list golden produced by PAYMENT") {
        Then("T-E1-4 the ADMIN copy is byte-identical to the PAYMENT golden") {
            val payment = File(System.getProperty("user.dir")).resolve("../payment-api/src/test/resources/contracts/admin-payment-list-v1.golden.json")
            payment.isFile shouldBe true
            resource.readBytes().toList() shouldBe payment.readBytes().toList()
        }

        Then("T-E1-3 the 11-key list parses and keeps paymentMethod in PAYMENT's key order") {
            val read = PaymentResponseShape.LIST.read(golden.get("list")) as Map<*, *>
            val item = (read["items"] as List<*>).first() as Map<*, *>
            item["paymentMethod"] shouldBe "CARD"
            item.keys.toList() shouldBe listOf(
                "paymentId", "environment", "merchantAccountId", "merchantOrderId", "orderNamePreview", "orderNameTruncated", "amountMinor", "currency", "paymentMethod",
                "status", "createdAt",
            )
        }

        Then("T-E1-3 control: the 10-key item (no paymentMethod), an unknown key and malformed paymentMethod values fail the shape") {
            parses(list().also { first(it).remove("paymentMethod") }) shouldBe false
            parses(list().also { first(it).put("merchantId", "mer_01JC0000000000000000000001") }) shouldBe false
            parses(list().also { first(it).put("paymentMethod", "card") }) shouldBe false
            parses(list().also { first(it).put("paymentMethod", "") }) shouldBe false
            parses(list().also { first(it).put("paymentMethod", 1) }) shouldBe false
            parses(list().also { first(it).putNull("paymentMethod") }) shouldBe false
            // 열거를 고정하지 않는다(D-ADMIN-UI-004 ⑤ — 수단 목록 구조): 형식에 맞는 새 코드는 통과하고 화면이 원문을 표시한다.
            parses(list().also { first(it).put("paymentMethod", "BANK_TRANSFER") }) shouldBe true
        }
    }
})
