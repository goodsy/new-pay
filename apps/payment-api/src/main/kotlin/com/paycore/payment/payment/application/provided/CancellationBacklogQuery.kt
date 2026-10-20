package com.paycore.payment.payment.application.provided

/** 취소 적체(CANCEL-001 §10 Observability — gauge 전용, 비권위): UNKNOWN 취소 수, 가장 오래된 UNKNOWN 연령(초 — 준비 시각부터, 없으면 0), 이관 뒤 닫히지 않은 회복 일정 수(ID 없음). */
data class CancellationBacklog(val unknownCancellations: Long, val oldestUnknownAgeSeconds: Long, val escalatedOpenSchedules: Long)

/** 취소 적체 관찰 조회(지금 시각 기준). 이관·적체가 늘어도 자동으로 해결하지 않는다(해결은 RECON — D-CANCEL1-003). */
interface CancellationBacklogQuery {
    fun backlog(): CancellationBacklog
}
