package com.paycore.admin.paymentops

import com.paycore.admin.paymentops.adapter.integration.AdminCallSigner
import com.paycore.admin.paymentops.adapter.integration.HttpPaymentOpsGateway
import com.paycore.admin.paymentops.adapter.integration.PaymentOpsSettings
import com.paycore.admin.paymentops.application.provided.CallPurpose
import com.paycore.admin.paymentops.application.provided.ExecutionResult
import com.paycore.admin.paymentops.application.provided.TargetResult
import com.paycore.admin.support.StubPaymentOpsServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * permit 용도 분리(ADMIN-PAYMENT-OPS-001 §4.7 L-8 — `LookupPermitIsolationSpec`): 화면 조회(`lookup`) permit을 모두 점유한 상태에서도 승인 A 단계 조회(`approval`)와
 * 실행 POST(`execution`)는 대기 없이 성공한다. 포화된 용도의 추가 호출은 PAYMENT를 부르지 않고 즉시 `PAYMENT_OPS_BUSY`. 실제 HTTP 대역이 조회 응답을 1.5초 늦춰
 * permit을 실제로 붙잡는다.
 */
class LookupPermitIsolationSpec : FunSpec({

    StubPaymentOpsServer.start()
    val settings = PaymentOpsSettings(
        "http://127.0.0.1:${StubPaymentOpsServer.PORT}", "adm-golden-k1", "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx", Duration.ofSeconds(1),
        Duration.ofSeconds(5), PaymentOpsSettings.Permits(1, 1, 1), 5, Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofHours(24),
        Duration.ofMinutes(5), 50, Duration.ofMinutes(2), 10, Duration.ofDays(1),
    )
    val gateway = HttpPaymentOpsGateway(settings, AdminCallSigner(settings.keyId, settings.signingKey())) { Instant.now() }

    test("a saturated lookup pool does not block approval lookups or execution commands; extra lookups are busy immediately") {
        val slow = "pay_01JC00000000000000000SLOW1"
        val fast = "pay_01JC00000000000000000FAST1"
        StubPaymentOpsServer.target(slow, StubPaymentOpsServer.Reply(200, "{\"error\":\"x\"}", delayMillis = 1_500))
        StubPaymentOpsServer.targetFound(fast)
        val approvalId = "apr_01JC0000000000000000LKP001"
        StubPaymentOpsServer.execution(approvalId, StubPaymentOpsServer.Reply(200, "{\"cancellationId\":\"pcn_01JC000000000000000000MK01\",\"status\":\"IN_PROGRESS\",\"existing\":false}"))
        val holder = CompletableFuture.supplyAsync { gateway.target(slow, CallPurpose.LOOKUP) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (StubPaymentOpsServer.requestsForPath("/internal/v1/admin/payments/$slow/operator-cancel-target").isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
        val started = System.nanoTime()
        val busy = gateway.target(fast, CallPurpose.LOOKUP)
        val approval = gateway.target(fast, CallPurpose.APPROVAL)
        val execution = gateway.execute(approvalId, "{\"approvalId\":\"$approvalId\"}".toByteArray())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        holder.get(10, TimeUnit.SECONDS)
        busy shouldBe TargetResult.Unavailable("PAYMENT_OPS_BUSY")
        approval.shouldBeInstanceOf<TargetResult.Found>()
        execution shouldBe ExecutionResult.Started("pcn_01JC000000000000000000MK01", false)
        (elapsedMs < 1_200) shouldBe true
    }
})
