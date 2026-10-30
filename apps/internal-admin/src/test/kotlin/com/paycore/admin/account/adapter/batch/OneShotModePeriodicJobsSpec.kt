package com.paycore.admin.account.adapter.batch

import com.paycore.admin.approval.adapter.batch.ApprovalExpiryJob
import com.paycore.admin.approval.adapter.batch.OperatorDecisionReconciliationScheduler
import com.paycore.admin.approval.adapter.batch.OwnerExecutionRecoveryScheduler
import com.paycore.admin.support.AdminDbTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.config.ScheduledTaskHolder

/**
 * 1회성 명령 모드에는 주기 job이 하나도 없다(ADMIN-BOOT-002 rev 0.3 — §4.9 "명령 모드는 스케줄러 없이"). 판정은 명령 모드 여부(`paycore.admin.command`) 하나 —
 * 각 job의 켬 설정과 무관하다. 여기서는 만료·회복·대사·gauge job을 **모두 켠 설정**으로 명령 모드를 띄운다(만료 job 켬이라 runner 자체는 모드 거부로 끝난다 — 이 시험은 빈만 본다).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "paycore.admin.approval.expiry-job.enabled=true", "paycore.admin.payment-ops.recovery.enabled=true",
        "paycore.admin.payment-ops.reconciliation.enabled=true", "paycore.admin.accounts.active-role-gauge.enabled=true",
    ],
    args = ["--paycore.admin.command=bootstrap", "--output=tty", "--security-admin-login=g-sa", "--security-admin-name=SA", "--second-security-admin-login=g-sa2", "--second-security-admin-name=SA2", "--approver-login=g-apr", "--approver-name=APR"],
)
@AdminDbTest
class OneShotModePeriodicJobsSpec(private val context: ApplicationContext, private val runner: OneShotCommandRunner) : FunSpec({
    test("command mode registers none of the periodic jobs (expiry, owner recovery, reconciliation, active role gauge) even when each is enabled") {
        listOf(ApprovalExpiryJob::class.java, OwnerExecutionRecoveryScheduler::class.java, OperatorDecisionReconciliationScheduler::class.java, ActiveRoleHolderGaugeScheduler::class.java)
            .map { type -> type.simpleName to (context.getBeanProvider(type).ifAvailable != null) } shouldBe listOf(
                "ApprovalExpiryJob" to false, "OwnerExecutionRecoveryScheduler" to false, "OperatorDecisionReconciliationScheduler" to false, "ActiveRoleHolderGaugeScheduler" to false,
            )
        // 어떤 경로로든(@Scheduled·SchedulingConfigurer) 예약된 작업이 0이다.
        context.getBeansOfType(ScheduledTaskHolder::class.java).values.flatMap { it.scheduledTasks }.map { it.toString() } shouldBe emptyList()
        runner.exitCode shouldBe OneShotExit.MODE_REFUSED.code
    }
})
