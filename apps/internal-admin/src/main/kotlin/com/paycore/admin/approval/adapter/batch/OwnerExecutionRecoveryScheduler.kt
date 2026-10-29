package com.paycore.admin.approval.adapter.batch

import com.paycore.admin.approval.application.provided.RecoverOwnerExecutionsUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 소유 서비스 실행 회복 job(ADMIN-PAYMENT-OPS-001 §4.16 — provided만 호출). 여러 인스턴스가 동시에 돌아도 선점이 `SKIP LOCKED`라 한 건은 한 번만 처리된다.
 * `paycore.admin.payment-ops.recovery.enabled=false`면 등록하지 않는다(시험은 끄고 Use Case를 직접 부른다). 주기는 Demo 30초(운영값 LIVE Gate).
 */
@Component
@ConditionalOnProperty(prefix = "paycore.admin.payment-ops.recovery", name = ["enabled"], havingValue = "true")
class OwnerExecutionRecoveryScheduler(private val recovery: RecoverOwnerExecutionsUseCase) {

    @Scheduled(fixedDelayString = "\${paycore.admin.payment-ops.recovery.fixed-delay}")
    fun run() {
        val claimed = recovery.recoverDue()
        if (claimed > 0) log.info("operation=owner-recovery result=claimed count={}", claimed)
    }

    private companion object {
        val log = LoggerFactory.getLogger(OwnerExecutionRecoveryScheduler::class.java)
    }
}
