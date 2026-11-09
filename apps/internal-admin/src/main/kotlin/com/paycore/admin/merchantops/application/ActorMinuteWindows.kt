package com.paycore.admin.merchantops.application

import java.util.concurrent.ConcurrentHashMap

/**
 * actor별 분 단위 고정 창 계수기(인스턴스 메모리 — 다중 인스턴스 합산·재기동 유지는 LIVE Gate, MERCHANT-OPS-002 L-9). [tryReserve]는 확인과 차감을 **한 동작**으로 한다(같은 actor의
 * 동시 요청이 남은 1회를 둘 다 쓰지 못한다 — C-13). [refund]는 같은 창일 때만, 0 아래로 내려가지 않게 1회를 돌려준다(창이 바뀌었으면 돌려주지 않는다).
 */
internal class ActorMinuteWindows(private val perMinute: () -> Int, private val clock: () -> Long = System::currentTimeMillis) {
    private val windows = ConcurrentHashMap<String, LongArray>()

    /** 예약 = 차감된 1회. [window]는 예약한 창 번호(환불 판정용). */
    data class Reservation(val actorId: String, val window: Long)

    /** 남은 횟수가 있으면 1회를 차감하고 예약을, 없으면 null. */
    fun tryReserve(actorId: String): Reservation? {
        val minute = clock() / MINUTE_MS
        val slot = windows.computeIfAbsent(actorId) { LongArray(2) }
        synchronized(slot) {
            if (slot[0] != minute) {
                slot[0] = minute
                slot[1] = 0
            }
            if (slot[1] >= perMinute()) return null
            slot[1]++
            return Reservation(actorId, minute)
        }
    }

    /** 예약한 창이 아직 현재 창이면 1회를 돌려준다(0 아래 없음). 창이 바뀌었으면 아무것도 하지 않는다. */
    fun refund(reservation: Reservation) {
        val slot = windows[reservation.actorId] ?: return
        synchronized(slot) {
            if (slot[0] == reservation.window && clock() / MINUTE_MS == reservation.window && slot[1] > 0) slot[1]--
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
