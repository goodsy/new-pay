package com.paycore.payment.payment.application.provided

import java.time.Duration

/** delivery 한 회차 입력(trigger 설정 — 기본값 없음). */
data class DeliverMerchantWebhooksCommand(val batchSize: Int) {
    init {
        require(batchSize >= 1) { "batch size must be positive" }
    }
}

/**
 * 한 회차 결과(지표용). [recordFailed]: 결과 반영 TX 또는 lease 부족 되돌림 TX 자체가 실패한 수(재시도 소진 포함 — 행은 lease 만료 뒤 다시 잡힌다,
 * 결과 반영 실패는 중복 전송 가능, at-least-once. 최종 검토 #3 n-1).
 * [claimLost]: 결과 반영 시 소유자가 아니었던 수 + lease 부족 되돌림이 false(그 사이 claim을 잃었거나 행 상태가 바뀜)였던 수(최종 검토 #3 n-1).
 * [released]: 남은 lease가 한 시도 예산을 덮지 못해 보내지 않고 실제로 되돌린(releaseClaim = true) 수(검토 #2 H-1).
 * [blocked]: 보낸 시도 중 목적지 문법·주소 정책으로 거부된 수(`DESTINATION_REJECTED`·`DESTINATION_BLOCKED` — 재시도·포기 수와 겹치는 원인 축, 최종 검토 m-1).
 * [attemptDurations]: 실제로 보낸 시도마다 걸린 시간(보낸 시도의 준비(이벤트 읽기·무결성 확인·조회)·서명·전송 시간 — 단조 시계, timer 입력). 되돌림·보내기 전 실패는 없다.
 */
data class MerchantWebhookDeliveryReport(
    val claimed: Int,
    val delivered: Int,
    val alreadyDelivered: Int,
    val retried: Int,
    val gaveUp: Int,
    val claimLost: Int,
    val recordFailed: Int,
    val released: Int,
    val blocked: Int,
    val attemptDurations: List<Duration>,
)

/** 가맹점 Webhook 전송(WEBHOOK-001 §5.0 delivery). batch adapter만 부른다. 호출은 TX 밖이어야 한다. */
interface DeliverMerchantWebhooksUseCase {
    fun deliver(command: DeliverMerchantWebhooksCommand): MerchantWebhookDeliveryReport
}
