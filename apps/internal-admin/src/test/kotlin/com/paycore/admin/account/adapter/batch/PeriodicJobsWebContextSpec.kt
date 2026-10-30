package com.paycore.admin.account.adapter.batch

import com.paycore.admin.approval.adapter.batch.ApprovalExpiryJob
import com.paycore.admin.approval.adapter.batch.OperatorDecisionReconciliationScheduler
import com.paycore.admin.approval.adapter.batch.OwnerExecutionRecoveryScheduler
import com.paycore.admin.support.AdminDbTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.config.ScheduledTaskHolder

/**
 * 반대쪽 확인(ADMIN-BOOT-002 최종 리뷰 Major-1): 명령 속성이 없는 웹 컨텍스트에서는 가드가 없고, 켠 주기 job 4개가 모두 등록·예약된다(가드가 서비스 중 job을 지우지 않는다).
 * 회복·대사 job은 기동 직후 한 번 돈다 — 시험 PAYMENT 대역이 없으면 그 실행은 실패로 기록될 뿐 이 시험은 빈·예약만 본다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "paycore.admin.approval.expiry-job.enabled=true", "paycore.admin.payment-ops.recovery.enabled=true",
        "paycore.admin.payment-ops.reconciliation.enabled=true", "paycore.admin.accounts.active-role-gauge.enabled=true",
        "paycore.admin.payment-ops.recovery.fixed-delay=1h", "paycore.admin.payment-ops.reconciliation.fixed-delay=1h",
        "paycore.admin.approval.expiry-job.fixed-delay=1h", "paycore.admin.accounts.active-role-gauge.fixed-delay=1h",
    ],
)
@AdminDbTest
class PeriodicJobsWebContextSpec(private val context: ApplicationContext) : FunSpec({
    test("a web context without the command property registers all four enabled periodic jobs and schedules them; the guard is absent") {
        listOf(ApprovalExpiryJob::class.java, OwnerExecutionRecoveryScheduler::class.java, OperatorDecisionReconciliationScheduler::class.java, ActiveRoleHolderGaugeScheduler::class.java)
            .map { type -> type.simpleName to (context.getBeanProvider(type).ifAvailable != null) } shouldBe listOf(
                "ApprovalExpiryJob" to true, "OwnerExecutionRecoveryScheduler" to true, "OperatorDecisionReconciliationScheduler" to true, "ActiveRoleHolderGaugeScheduler" to true,
            )
        context.getBeanProvider(OneShotModePeriodicJobGuard::class.java).ifAvailable shouldBe null
        context.getBeansOfType(ScheduledTaskHolder::class.java).values.sumOf { it.scheduledTasks.size } shouldBeGreaterThan 3
    }
})
