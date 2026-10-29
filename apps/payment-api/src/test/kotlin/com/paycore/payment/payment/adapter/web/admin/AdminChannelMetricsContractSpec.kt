package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.application.provided.CancellationView
import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmOperatorApprovalDeadlineUseCase
import com.paycore.payment.payment.application.provided.OperatorCancelRejectionCode
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.application.provided.OperatorDeadlineConfirmation
import com.paycore.payment.payment.application.provided.OperatorDecisionQuery
import com.paycore.payment.payment.application.provided.OperatorDecisionState
import com.paycore.payment.payment.application.provided.OperatorDecisionView
import com.paycore.payment.payment.application.provided.OperatorQueryFailure
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.ReturnStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Instant

/**
 * ADMIN 채널 지표 이름·label 묶음(ADMIN-PAYMENT-OPS-001 §6, S9 — I-59 결정 지표 포함, 서블릿 mock): 진입 필터·실행·기한 확인·결정 조회·bulkhead가 남기는 지표는 정확히
 * `auth{reason,result}`·`request{endpoint,result}`·`busy{endpoint}`·`decision{decision,replayed}`·`query_timeout{endpoint}`이고 label 값은 고정 분류뿐이다(paymentId·approvalId 없음).
 * 결정 지표: 실행 STARTED(재생 = existing)·업무 거절 REJECTED(재생 여부)·PAYMENT_NOT_FOUND(재생 여부를 결과가 구분하지 않아 `unknown` — I-134), 파생 거절(APPROVAL_CONFLICT·
 * IDENTITY_MISMATCH)은 결정이 아니라 세지 않는다. 기한 확인은 이번에 기록(confirmed)이면 재생 아님, 기존 결정이면 재생, NONE은 세지 않는다.
 */
class AdminChannelMetricsContractSpec : BehaviorSpec({

    Given("every admin channel path that records a metric, on one registry") {
        val registry = SimpleMeterRegistry()
        val permits = AdminChannelPermits(AdminOpsSettings(readPermits = 4, commandPermits = 1, publicPoolReserve = 4), registry)
        val view = CancellationView(
            PaymentId("pay_x"), CancellationId("pcn_1"), CancellationViewStatus.IN_PROGRESS, Money(7000, CurrencyCode("KRW")), null, null, null, null, 3000, ReturnStatus.NONE,
        )
        val body = AdminOpsTestClient.body(null, paymentId = AdminOpsTestClient.newPaymentId(), amountMinor = 7000, currency = "KRW")
        fun execute(result: OperatorCorrectionCancelResult) {
            val useCase = mockk<OperatorCorrectionCancelUseCase> { every { cancel(any()) } returns result }
            AdminOperatorCancellationController(useCase, mockk(relaxed = true), permits, registry)
                .execute(MockHttpServletRequest("POST", AdminOpsTestClient.EXECUTE).apply { setContent(body.toByteArray()) })
        }
        fun confirm(decision: OperatorDecisionView, confirmed: Boolean) {
            val approvalId = AdminOpsTestClient.newApprovalId()
            val b = AdminOpsTestClient.body(null, approvalId = approvalId, paymentId = AdminOpsTestClient.newPaymentId(), amountMinor = 7000, currency = "KRW")
            val useCase = mockk<ConfirmOperatorApprovalDeadlineUseCase> { every { confirm(any()) } returns OperatorDeadlineConfirmation(decision, confirmed) }
            OperatorDeadlineConfirmationController(useCase, mockk(relaxed = true), permits, registry)
                .confirm(approvalId, MockHttpServletRequest("POST", AdminOpsTestClient.deadline(approvalId)).apply { setContent(b.toByteArray()) })
        }
        fun counter(decision: String, replayed: String) = registry.find(DECISION).tags("decision", decision, "replayed", replayed).counter()?.count() ?: 0.0

        // 진입 필터(서명 없음 → 401).
        val path = AdminOpsTestClient.EXECUTE
        AdminChannelAuthenticationFilter(AdminChannelFixture.settings(), registry) { Instant.parse("2026-10-27T01:02:03Z") }
            .doFilter(MockHttpServletRequest("POST", path).apply { servletPath = path; setContent("{}".toByteArray()) }, MockHttpServletResponse(), FilterChain { _, _ -> })
        // 실행 결과 전부.
        execute(OperatorCorrectionCancelResult.Accepted(view, existing = false))
        execute(OperatorCorrectionCancelResult.Accepted(view, existing = true))
        execute(OperatorCorrectionCancelResult.Rejected(OperatorCancelRejectionCode.CANCEL_IN_PROGRESS, replayed = false))
        execute(OperatorCorrectionCancelResult.Rejected(OperatorCancelRejectionCode.CANCEL_IN_PROGRESS, replayed = true))
        execute(OperatorCorrectionCancelResult.Rejected(OperatorCancelRejectionCode.APPROVAL_CONFLICT))
        execute(OperatorCorrectionCancelResult.NotFound)
        // 기한 확인: 이번 기록, 기존 거절, 기존 시작, 결정 없음.
        confirm(OperatorDecisionView(OperatorDecisionState.REJECTED, code = "APPROVAL_EXECUTION_EXPIRED"), confirmed = true)
        confirm(OperatorDecisionView(OperatorDecisionState.REJECTED, code = "CANCEL_IN_PROGRESS"), confirmed = false)
        confirm(OperatorDecisionView(OperatorDecisionState.STARTED, cancellationId = "pcn_1", cancellationStatus = CancellationViewStatus.IN_PROGRESS), confirmed = false)
        confirm(OperatorDecisionView.NONE, confirmed = false)
        // bulkhead busy, 결정 조회 시간 초과.
        permits.command("execute", { Unit }) { permits.command("execute", { Unit }) { Unit } }
        val query = mockk<OperatorDecisionQuery> { every { decision(any()) } throws OperatorQueryFailure.TimedOut(null) }
        OperatorDecisionController(query, permits, registry).decision(AdminOpsTestClient.newApprovalId(), MockHttpServletRequest("GET", "/internal/v1/admin/operator-cancellations/x"))

        Then("the admin channel meters are exactly the §6 names with their fixed label keys") {
            registry.meters.filter { it.id.name.startsWith(PREFIX) }.groupBy({ it.id.name }, { it.id.tags.map { t -> t.key }.sorted() })
                .mapValues { (_, keys) -> keys.toSet() } shouldBe mapOf(
                "${PREFIX}auth" to setOf(listOf("reason", "result")),
                "${PREFIX}request" to setOf(listOf("endpoint", "result")),
                "${PREFIX}busy" to setOf(listOf("endpoint")),
                DECISION to setOf(listOf("decision", "replayed")),
                "${PREFIX}query_timeout" to setOf(listOf("endpoint")),
            )
        }

        Then("I-59: decision{decision,replayed} counts new and replayed decisions; derived rejections and NONE are not decisions") {
            listOf(counter("STARTED", "false"), counter("STARTED", "true"), counter("REJECTED", "false"), counter("REJECTED", "true"), counter("REJECTED", "unknown")) shouldBe
                listOf(1.0, 2.0, 2.0, 2.0, 1.0)
            registry.find(DECISION).counters().sumOf { it.count() } shouldBe 8.0
            registry.meters.flatMap { it.id.tags }.map { it.value }.filter { it.startsWith("pay_") || it.startsWith("apr_") || it.startsWith("pcn_") } shouldBe emptyList()
        }
    }
}) {
    private companion object {
        const val PREFIX = "paycore.payment.admin_channel."
        const val DECISION = "paycore.payment.admin_channel.decision"
    }
}
