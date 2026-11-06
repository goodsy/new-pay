package com.paycore.financial.acquiring.application

import com.paycore.financial.acquiring.application.provided.WaitingEntry
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 마지막 봉인이 판정한 대기 현황(2-F — 대기 사유별 체류 지표 원천). 대기(Wait)는 기록하지 않는 판정이라(다음 봉인에서 다시 본다) DB count로 셀 수 없다 — 봉인 TX가
 * 커밋된 뒤 lane별로 통째로 바꿔 둔다. 프로세스 안 값이다(재기동 뒤 첫 봉인 전에는 비어 있고, 인스턴스마다 자기가 봉인한 lane만 안다 — 장기 체류의 영속 기록·경보
 * 임계는 LIVE Gate §14-12).
 */
@Component
class AcquisitionWaitingBoard {
    private val byLane = ConcurrentHashMap<String, LaneWaiting>()

    /** lane의 마지막 봉인 대기 현황을 바꾼다. */
    fun report(lane: String, entries: List<WaitingEntry>, cancelReconcile: Int) {
        byLane[lane] = LaneWaiting(entries.filter { it.lane == lane }, cancelReconcile)
    }

    fun waiting(): List<WaitingEntry> = byLane.values.flatMap { it.entries }.sortedWith(compareBy({ it.lane }, { it.kind }, { it.reason }))

    fun cancelReconcile(): Map<String, Int> = byLane.mapValues { it.value.cancelReconcile }.toSortedMap()

    private class LaneWaiting(val entries: List<WaitingEntry>, val cancelReconcile: Int)

    internal companion object {
        /** 대기 묶음 집계: 사유별 건수·가장 오래된 시각. */
        fun group(lane: String, kind: String, waits: List<Pair<String, Instant>>): List<WaitingEntry> =
            waits.groupBy({ it.first }, { it.second }).map { (reason, times) -> WaitingEntry(lane, kind, reason, times.size, times.min()) }
    }
}
