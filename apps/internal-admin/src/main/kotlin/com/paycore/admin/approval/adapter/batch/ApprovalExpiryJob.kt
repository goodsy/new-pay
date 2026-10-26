package com.paycore.admin.approval.adapter.batch

import com.paycore.admin.approval.application.provided.ApprovalUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 승인 만료 정리 job(§4.7.2 n-5 — 화면 표시용, **권위 판정은 승인 TX**). 행마다 짧은 TX(승인 행 잠금 → 같은 판정 함수 → EXPIRED·감사). provided만 호출한다.
 * `paycore.admin.approval.expiry-job.enabled=false`면 등록하지 않는다(시험은 끄고 Use Case를 직접 부른다).
 */
@Component
@ConditionalOnProperty(prefix = "paycore.admin.approval.expiry-job", name = ["enabled"], havingValue = "true")
class ApprovalExpiryJob(private val approvals: ApprovalUseCase) {

    @Scheduled(fixedDelayString = "\${paycore.admin.approval.expiry-job.fixed-delay}")
    fun run() {
        val expired = approvals.expireDue()
        if (expired > 0) log.info("operation=approval-expiry result=expired count={}", expired)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ApprovalExpiryJob::class.java)
    }
}
