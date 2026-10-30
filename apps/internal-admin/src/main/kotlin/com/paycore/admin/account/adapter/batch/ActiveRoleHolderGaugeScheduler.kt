package com.paycore.admin.account.adapter.batch

import com.paycore.admin.account.application.provided.RefreshActiveRoleHoldersUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 활성 역할 보유자 gauge 갱신 job(D-ADMIN1-023 — provided만 호출, Demo 1분·운영 주기 LIVE Gate). 기동 직후 한 번, 이후 고정 지연.
 * 등록 조건: `paycore.admin.accounts.active-role-gauge.enabled=true`(다른 주기 job과 같은 관례). 1회성 명령 모드에서는 켜져 있어도
 * [OneShotModePeriodicJobGuard]가 모든 주기 job과 함께 지운다(I-11 — 판정은 명령 모드 여부 하나).
 * 실패는 한 줄 경고(값 없음)만 남기고 다음 주기에 다시 시도한다 — 직전 gauge 값은 남는다.
 */
@Component
@ConditionalOnProperty(prefix = "paycore.admin.accounts.active-role-gauge", name = ["enabled"], havingValue = "true")
class ActiveRoleHolderGaugeScheduler(private val gauges: RefreshActiveRoleHoldersUseCase) {

    @Scheduled(fixedDelayString = "\${paycore.admin.accounts.active-role-gauge.fixed-delay}")
    fun run() {
        try {
            gauges.refresh()
        } catch (e: RuntimeException) {
            log.warn("operation=active-role-gauge result=error failure={}", e.javaClass.simpleName)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ActiveRoleHolderGaugeScheduler::class.java)
    }
}
