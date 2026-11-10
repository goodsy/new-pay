package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.domain.ApprovalWindow
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 2인 승인 근거 실행 창 설정(MERCHANT-OPS-002 §9.3 판정 2·C-19 — `paycore.master.admin-channel.approval`). **기본값이 없다**(없으면 바인딩 실패 → 웹 프로세스 기동 실패).
 * Demo 값 `max-execution-window` PT24H·`max-clock-skew` PT1M — 운영값 아님(LIVE Gate). web adapter가 명령마다 [window]를 Use Case 명령에 넣는다(비웹 seed 컨텍스트에는 이 bean이 없다).
 *
 * @param maxExecutionWindow 승인 시각 → 실행 기한 최대 길이(양수, 최대 7일 — 기술 상한).
 * @param maxClockSkew ADMIN 승인 시각이 MASTER 시각보다 앞서도 되는 최대 차이(0..PT10M — 기술 상한).
 */
@ConfigurationProperties(prefix = "paycore.master.admin-channel.approval")
class AdminApprovalSettings(val maxExecutionWindow: Duration, val maxClockSkew: Duration) {
    init {
        require(!maxExecutionWindow.isNegative && !maxExecutionWindow.isZero && maxExecutionWindow <= MAX_EXECUTION) {
            "admin-channel approval max-execution-window must be within (0, 7d]"
        }
        require(!maxClockSkew.isNegative && maxClockSkew <= MAX_SKEW) { "admin-channel approval max-clock-skew must be within [0, 10m]" }
    }

    val window: ApprovalWindow get() = ApprovalWindow(maxExecutionWindow, maxClockSkew)

    override fun toString(): String = "AdminApprovalSettings(maxExecutionWindow=$maxExecutionWindow, maxClockSkew=$maxClockSkew)"

    private companion object {
        val MAX_EXECUTION: Duration = Duration.ofDays(7)
        val MAX_SKEW: Duration = Duration.ofMinutes(10)
    }
}
