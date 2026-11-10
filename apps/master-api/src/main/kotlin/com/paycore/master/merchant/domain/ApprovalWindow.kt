package com.paycore.master.merchant.domain

import java.time.Duration

/**
 * 승인 근거 실행 창(MERCHANT-OPS-002 §9.3 판정 2 — D-MOPS-007 ①, D-ADMIN-007): [maxExecution] = 승인 시각에서 실행 기한까지 허용하는 최대 길이,
 * [maxClockSkew] = ADMIN 승인 시각이 MASTER 시각보다 앞서도 되는 최대 차이. 설정(`paycore.master.admin-channel.approval.*`, 기본값 없음)에서 Application으로 전달한다.
 * Demo 값(PT24H·PT1M)은 운영값이 아니다(LIVE Gate).
 */
data class ApprovalWindow(val maxExecution: Duration, val maxClockSkew: Duration) {
    init {
        require(!maxExecution.isNegative && !maxExecution.isZero) { "max execution window must be positive" }
        require(!maxClockSkew.isNegative) { "max clock skew must not be negative" }
    }
}
