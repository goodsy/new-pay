package com.paycore.payment.payment.application.provided

import java.time.Duration

/** fan-out 한 회차 입력(trigger 설정 — 기본값 없음, WH-17). [failedRetryInterval]: 실패 행의 다음 시도까지(Demo 1분). */
data class FanOutMerchantNotificationsCommand(val batchSize: Int, val failedRetryInterval: Duration) {
    init {
        require(batchSize >= 1) { "batch size must be positive" }
        require(failedRetryInterval.isPositive) { "failed retry interval must be positive" }
    }
}

/**
 * 한 회차 결과. [claimed] = 후보 수, [deferred] = 같은 Payment의 앞 행이 이번 회차에 실패해 건너뛴 행, [failed] = 비종결 실패(FANOUT_FAILED)로 둔 행(경보 대상),
 * [noEndpoint] = 이벤트는 기록했지만 endpoint가 없어 전달 행을 만들지 않은 수([notified]에 포함).
 */
data class MerchantNotificationFanoutReport(
    val claimed: Int,
    val notified: Int,
    val noEndpoint: Int,
    val suppressed: Int,
    val notApplicable: Int,
    val failed: Int,
    val deferred: Int,
)

/** Outbox → 가맹점 이벤트 fan-out(WEBHOOK-001 §5.0 rev 0.2). batch adapter만 부른다. 호출은 TX 밖이어야 한다(행마다 자기 로컬 TX). */
interface FanOutMerchantNotificationsUseCase {
    fun fanOut(command: FanOutMerchantNotificationsCommand): MerchantNotificationFanoutReport
}
