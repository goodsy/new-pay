package com.paycore.admin.approval

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.approval.application.OperatorCancelJustificationService
import com.paycore.admin.approval.application.provided.JustificationResult
import com.paycore.admin.approval.application.required.AcquiringOpsCommandStore
import com.paycore.admin.approval.application.required.ApprovalEnvironment
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.OperatorCancelCommandStore
import com.paycore.admin.approval.application.required.OwnerOpsMetrics
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.transaction.PlatformTransactionManager
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant

/**
 * 근거·사유 조회 속도 제한 창(I-131, S9 nit — 단위): 분 단위 고정 창은 승인 모듈 시계([ApprovalEnvironment.now])를 따른다(시계 대역으로 창이 넘어가면 다시 허용). 형식이 아닌 ID는 TX·DB 없이
 * 404라 저장소·감사 대역은 불리지 않는다(불리면 실패하는 Proxy).
 */
private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
    if (m.name == "toString") "unused" else error("unexpected call ${m.name}")
} as T

class OperatorCancelJustificationWindowSpec : FunSpec({

    class Clock(var now: Instant) : ApprovalEnvironment {
        override fun now(): Instant = now
        override fun newApprovalId(): String = error("not used")
        override val validity: Duration = Duration.ofHours(24)
        override val justificationPerMinute: Int = 2
    }

    test("the per-actor window follows ApprovalEnvironment.now(): over the limit is RateLimited, the next minute (by that clock) admits again") {
        val clock = Clock(Instant.parse("2026-10-29T00:00:10Z"))
        var limited = 0
        val metrics = Proxy.newProxyInstance(OwnerOpsMetrics::class.java.classLoader, arrayOf(OwnerOpsMetrics::class.java)) { _, m, _ ->
            if (m.name == "justificationRateLimited") limited++ else if (m.name != "toString") error("unexpected ${m.name}")
            null
        } as OwnerOpsMetrics
        val service = OperatorCancelJustificationService(
            unused<PlatformTransactionManager>(), unused<AdminSessionQuery>(), unused<ApprovalRequestRepository>(), unused<OperatorCancelCommandStore>(),
            unused<AdminAuditRecorder>(), clock, metrics, unused<AcquiringOpsCommandStore>(),
            // MERCHANT-OPS-002 B2-2: 가맹점 승인 명령 Store(이 시험은 쓰지 않는다 — 기대값 불변).
            unused<com.paycore.admin.approval.application.required.MerchantOpsCommandStore>(),
        )
        val context = AdminRequestContext("corr-window", null)
        fun ask() = service.view("adm_viewer", "not-an-approval-id", context)
        listOf(ask(), ask(), ask()) shouldBe listOf(JustificationResult.NotFound, JustificationResult.NotFound, JustificationResult.RateLimited)
        clock.now = clock.now.plusSeconds(60)
        ask() shouldBe JustificationResult.NotFound
        limited shouldBe 1
    }
})
