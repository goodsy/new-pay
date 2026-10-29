package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.paymentops.application.provided.LookupResult
import com.paycore.admin.paymentops.application.provided.PaymentTargetLookupUseCase
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubPaymentOpsServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * 전용 운영 지표(ADMIN-PAYMENT-OPS-001 §6·§4.16 — I-83 임시 재사용 교체). label은 고정 분류값만(승인 ID·paymentId·actorId 없음):
 * - `paycore.admin.ops.call{operation,result}`: PAYMENT 호출 결과(operation `TARGET`·`EXECUTE`, result = 결과 분류).
 * - `paycore.admin.ops.integrity_alert{code}`: 무결성·운영 경보(파생 409 code, 400 형식, 401, `APPROVAL_WINDOW_INVALID`, 고정 값 불일치).
 * - `paycore.admin.ops.lookup_rate_limited`: 화면 조회 속도 제한(429).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class OwnerOpsMetricsSpec(
    owners: OwnerServiceApprovalUseCase,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    private val lookup: PaymentTargetLookupUseCase,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    val fixture = OwnerApprovalFixture(owners, path, ready, storage)
    fun count(name: String, vararg tags: String): Double = registry.find(name).tags(*tags).counters().sumOf { it.count() }

    Given("owner-service calls with a derived conflict, an owner rejection and a rate-limited lookup") {
        Then("call results, integrity alerts and rate limiting are counted with fixed labels only") {
            val before = listOf(
                count("paycore.admin.ops.call", "operation", "EXECUTE", "result", "UNKNOWN"), count("paycore.admin.ops.call", "operation", "EXECUTE", "result", "OWNER_REJECTED"),
                count("paycore.admin.ops.integrity_alert", "code", "APPROVAL_CONFLICT"), count("paycore.admin.ops.integrity_alert", "code", "REQUEST_MALFORMED"),
                count("paycore.admin.ops.lookup_rate_limited"),
            )
            val (conflict, _) = fixture.requested()
            StubPaymentOpsServer.execution(conflict, StubPaymentOpsServer.Reply(409, "{\"error\":\"APPROVAL_CONFLICT\"}"))
            fixture.approve(conflict)
            val (malformed, _) = fixture.requested()
            StubPaymentOpsServer.execution(malformed, StubPaymentOpsServer.Reply(400, "{\"error\":\"REQUEST_MALFORMED\"}"))
            fixture.approve(malformed)
            val viewer = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.VIEWER) }.account.id.value
            val pay = fixture.newPaymentId().also { StubPaymentOpsServer.targetFound(it) }
            val limited = (1..12).map { lookup.lookup(viewer, pay) }.count { it == LookupResult.RateLimited }
            (limited > 0) shouldBe true
            val after = listOf(
                count("paycore.admin.ops.call", "operation", "EXECUTE", "result", "UNKNOWN"), count("paycore.admin.ops.call", "operation", "EXECUTE", "result", "OWNER_REJECTED"),
                count("paycore.admin.ops.integrity_alert", "code", "APPROVAL_CONFLICT"), count("paycore.admin.ops.integrity_alert", "code", "REQUEST_MALFORMED"),
                count("paycore.admin.ops.lookup_rate_limited"),
            )
            after.zip(before).map { (a, b) -> a - b } shouldBe listOf(1.0, 1.0, 1.0, 1.0, limited.toDouble())
            registry.find("paycore.admin.ops.call").counters().flatMap { c -> c.id.tags.map { it.key } }.toSet() shouldBe setOf("operation", "result")
        }
    }
})
