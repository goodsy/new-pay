package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.application.provided.CancelPaymentFailure
import com.paycore.payment.payment.application.provided.CancellationView
import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.provided.OperatorCancelRejectionCode
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.application.provided.OperatorDecisionState
import com.paycore.payment.payment.application.provided.OperatorDecisionView
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.ReturnStatus
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * API 계약 golden ③(ADMIN-PAYMENT-OPS-001 §4.19 ③ `AdminOpsApiContractSpec` — harness `test_admin_payment_ops_api_golden.py`가 두 앱과 독립 생성): PAYMENT 해석기는 요청 예를 받고 엄격 거부 예를
 * 400 REQUEST_MALFORMED로 판정하며, PAYMENT 응답 본문(실행 매핑·결정 조회·기한 확인·결정 목록)은 golden과 필드·값이 같다(자리표시는 정규식). 실행 매핑은 실제 컨트롤러에 Use Case 결과를
 * 넣어 만든다(시나리오별 실제 DB 실행은 AdminOperatorCancellationApiSpec — I-58).
 */
class AdminOpsApiContractSpec : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val golden = mapper.readTree(ClassPathResource("contracts/admin-payment-ops-api-v1.golden.json").inputStream)
    val placeholders = golden.get("placeholders").properties().associate { it.key to Regex(it.value.asString()) }
    val settings = AdminOpsSettings(4, 2, 4)
    fun JsonNode.items() = iterator().asSequence().toList()

    /** golden 값과 실제 값 비교: `{"$placeholder": NAME}`은 실제 값 문자열이 정규식에 맞으면 같다. 객체는 필드 순서·집합까지 같아야 한다. */
    fun matches(expected: JsonNode, actual: JsonNode?, path: String = "$") {
        withClue(path) {
            if (expected.isObject && expected.has("\$placeholder")) {
                val text = if (actual!!.isNumber) actual.asString() else actual.asString()
                placeholders.getValue(expected.get("\$placeholder").asString()).matches(text) shouldBe true
                return@withClue
            }
            if (expected.isObject) {
                actual!!.propertyNames().toList() shouldBe expected.propertyNames().toList()
                expected.properties().forEach { (k, v) -> matches(v, actual.get(k), "$path.$k") }
            } else if (expected.isArray) {
                actual!!.size() shouldBe expected.size()
                expected.items().forEachIndexed { i, v -> matches(v, actual.get(i), "$path[$i]") }
            } else if (expected.isNumber) {
                actual!!.isIntegralNumber shouldBe true
                actual.asString() shouldBe expected.asString()
            } else {
                actual shouldBe expected
            }
        }
    }

    fun execute(useCase: OperatorCorrectionCancelUseCase): Pair<Int, JsonNode> {
        val r = AdminOperatorCancellationController(useCase, mockk(relaxed = true), AdminChannelPermits(settings, SimpleMeterRegistry()), SimpleMeterRegistry())
            .execute(MockHttpServletRequest("POST", "/").apply { setContent(golden.get("requestExamples").first().get("body").asString().toByteArray()) })
        return r.statusCode.value() to mapper.readTree(r.body)
    }

    val pcn = "pcn_01JC0000000000000000000009"
    val t = Instant.parse("2026-10-27T05:06:07.890Z")

    fun view(state: OperatorDecisionState, code: String? = null) = OperatorDecisionView(
        state, code, if (state == OperatorDecisionState.STARTED) pcn else null, if (state == OperatorDecisionState.STARTED) CancellationViewStatus.COMPLETED else null,
        "pay_01JC0000000000000000000001", 7000, "KRW", "adm_01JC00000000000000000000R1", "adm_01JC00000000000000000000A1", "oct1:" + "0".repeat(64), "opd1:" + "1".repeat(64),
        t, false, 12, t, "EXECUTION_COMMAND", "apr_01JC0000000000000000000001",
    )

    Given("requests") {
        Then("every request example parses into a command") {
            golden.get("requestExamples").items().forEach {
                withClue(it.get("name").asString()) {
                    AdminOperatorCancellationRequestParser.parse(it.get("body").asString().toByteArray(Charsets.UTF_8), "req")
                        .shouldBeInstanceOf<AdminOperatorCancellationRequestParser.Parsed.Ok>()
                }
            }
        }
        Then("every strict rejection is REQUEST_MALFORMED") {
            golden.get("strictRejections").items().forEach {
                withClue(it.get("name").asString()) {
                    AdminOperatorCancellationRequestParser.parse(it.get("body").asString().toByteArray(Charsets.UTF_8), "req")
                        .shouldBeInstanceOf<AdminOperatorCancellationRequestParser.Parsed.Malformed>().audit.code.name shouldBe it.get("error").asString()
                }
            }
        }
    }

    Given("execution responses") {
        Then("each golden case is what PAYMENT answers for the matching result") {
            golden.get("responseCases").items().forEach { c ->
                val name = c.get("name").asString()
                val status = c.get("httpStatus").asInt()
                val code = c.get("body").get("error")?.asString()
                withClue(name) {
                    when {
                        status == 200 -> {
                            val existing = c.get("body").get("existing").asBoolean()
                            val s = CancellationViewStatus.valueOf(c.get("body").get("status").asString())
                            val v = CancellationView(PaymentId("pay_x"), CancellationId(pcn), s, Money(7000, CurrencyCode("KRW")), null, null, null, null, 0, ReturnStatus.NONE)
                            val (st, body) = execute(mockk { every { cancel(any()) } returns OperatorCorrectionCancelResult.Accepted(v, existing) })
                            st shouldBe 200
                            matches(c.get("body"), body)
                        }
                        status == 409 || (status == 404) -> {
                            val result = if (status == 404) OperatorCorrectionCancelResult.NotFound
                            else OperatorCorrectionCancelResult.Rejected(OperatorCancelRejectionCode.valueOf(code!!))
                            execute(mockk { every { cancel(any()) } returns result }).let { (st, body) -> st shouldBe status; matches(c.get("body"), body) }
                        }
                        status == 400 -> {
                            OperatorCancelRejectionCode.valueOf(code!!).malformed shouldBe true
                            matches(c.get("body"), mapper.readTree(AdminChannelResponses.error(MockHttpServletRequest(), 400, code).body))
                        }
                        status == 401 -> matches(c.get("body"), mapper.readTree("""{"error":"${AdminChannelResponses.UNAUTHENTICATED}"}"""))
                        code == "ADMIN_COMMAND_BUSY" -> matches(c.get("body"), mapper.readTree(AdminChannelResponses.error(MockHttpServletRequest(), 503, AdminChannelResponses.ADMIN_COMMAND_BUSY).body))
                        else -> {
                            val e = if (code == "OUTCOME_UNKNOWN") CancelPaymentFailure.OutcomeUnknown(RuntimeException()) else CancelPaymentFailure.TemporarilyUnavailable("X")
                            execute(mockk { every { cancel(any()) } throws e }).let { (st, body) -> st shouldBe 503; matches(c.get("body"), body) }
                        }
                    }
                }
            }
        }
    }

    Given("decision, deadline and list responses") {
        Then("the decision views render exactly the golden shapes") {
            val byName = golden.get("decisionResponses").items().associateBy { it.get("name").asString() }
            matches(byName.getValue("started").get("body"), mapper.valueToTree(AdminChannelResponses.decision(view(OperatorDecisionState.STARTED))))
            matches(byName.getValue("rejected").get("body"), mapper.valueToTree(AdminChannelResponses.decision(view(OperatorDecisionState.REJECTED, "OPERATOR_TARGET_STALE"))))
            matches(byName.getValue("none").get("body"), mapper.valueToTree(AdminChannelResponses.decision(OperatorDecisionView.NONE)))
        }
        Then("the deadline confirmation bodies are the decision shape plus confirmed") {
            val byName = golden.get("deadlineResponses").items().associateBy { it.get("name").asString() }
            fun render(v: OperatorDecisionView, confirmed: Boolean): JsonNode = mapper.valueToTree(AdminChannelResponses.decision(v).also { it["confirmed"] = confirmed })
            matches(byName.getValue("expired-confirmed").get("body"), render(view(OperatorDecisionState.REJECTED, "APPROVAL_EXECUTION_EXPIRED"), true))
            matches(byName.getValue("none-before-deadline").get("body"), render(OperatorDecisionView.NONE, false))
            matches(byName.getValue("existing-started").get("body"), render(view(OperatorDecisionState.STARTED), false))
            matches(byName.getValue("existing-rejected").get("body"), render(view(OperatorDecisionState.REJECTED, "OPERATOR_TARGET_STALE"), false))
        }
        Then("a list page item has exactly the golden fields (no reason or reference)") {
            val item = view(OperatorDecisionState.REJECTED, "PAYMENT_NOT_FOUND")
            val page = linkedMapOf("items" to listOf(AdminChannelResponses.listItem(item)), "nextWatermark" to 12L, "serverTime" to AdminChannelResponses.time(t))
            matches(golden.get("listPage"), mapper.valueToTree(page))
        }
    }
})
