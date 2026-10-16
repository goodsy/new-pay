package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 효력 기간 [from, to)(UTC 시각, D-MD-005·D-MD-004). [to]가 없으면 종료 예정 없음.
 * 저장 정밀도는 ms(DATETIME(3))다. 명령 경계는 [ofMillis]로 ms로 자른 기간만 쓴다(MySQL은 소수 초를 **반올림**하므로 ms 미만 값을 그대로
 * 넘기면 저장 값·fingerprint가 입력과 달라진다).
 */
data class EffectivePeriod(val from: Instant, val to: Instant? = null) {
    init {
        require(to == null || to.isAfter(from)) { "effective period must end after it starts" }
    }

    /** ms 정밀도인가(저장 값과 같은가). */
    val isMillisPrecise: Boolean get() = from.nano % NANOS_PER_MILLI == 0 && (to == null || to.nano % NANOS_PER_MILLI == 0)

    fun contains(at: Instant): Boolean = !at.isBefore(from) && (to == null || at.isBefore(to))

    /** 이미 끝났다(종료 시각이 [at] 이전이거나 같다). */
    fun hasEndedAt(at: Instant): Boolean = to != null && !at.isBefore(to)

    fun overlaps(other: EffectivePeriod): Boolean =
        (other.to == null || from.isBefore(other.to)) && (to == null || other.from.isBefore(to))

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000

        /**
         * 명령 입력을 저장 정밀도(ms, 버림)로 자른 기간. 잘라서 비게 되면(예: 0.1ms~0.4ms) 업무 거절 [MasterRejection.EFFECTIVE_PERIOD_EMPTY]
         * (DB에서 CHECK 위반이나 반올림으로 다른 기간이 저장되는 대신).
         */
        fun ofMillis(from: Instant, to: Instant?): EffectivePeriod {
            val f = from.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            val t = to?.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            if (t != null && !t.isAfter(f)) throw MasterRuleViolation(MasterRejection.EFFECTIVE_PERIOD_EMPTY)
            return EffectivePeriod(f, t)
        }
    }
}
