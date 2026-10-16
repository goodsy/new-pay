package com.paycore.payment.payment.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 일 한도 날짜 = 승인 시작 시각의 Asia/Seoul 날짜, 점유 때 고정(D-AUTH-007, 사용자 확정 2026-10-15). */
object LimitBusinessDate {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    fun of(authorizationStartedAt: Instant): LocalDate = authorizationStartedAt.atZone(ZONE).toLocalDate()
}

/** 일 한도 사용량의 키: 환경별 사용량(D-AUTH-006) × MID × 통화 × 업무일. */
data class LimitUsageKey(
    val environment: Environment,
    val merchantAccountId: MerchantAccountId,
    val currency: CurrencyCode,
    val businessDate: LocalDate,
)

enum class LimitRejection { PER_TRANSACTION_LIMIT_EXCEEDED, DAILY_LIMIT_EXCEEDED }

/**
 * 점유 상태(D-AUTH-007·D-AUTH-010):
 * - RESERVED →(승인) CONSUMED, RESERVED →(확정 거절·전송 전 실패 확정·무효과 확정) RELEASED.
 * - RELEASED →(같은 승인 operation의 늦은 승인, SUCCESS_AFTER_TERMINATION hold) FORCED: 승인 시작일 날짜에 다시 기록한 점유. 한도를 넘었다는
 *   이유로 기록을 거부하지 않고 초과 여부를 표시하며, 기관에 실제 승인이 있을 수 있는 동안 **새 승인의 한도 판정 합에 포함**한다(D-AUTH-010).
 * - FORCED →(hold 해제 판정 NO_EFFECT) RELEASED, FORCED →(REAL_APPROVAL·DOUBLE_APPROVAL_CORRECTED) CONSUMED(취소 미복원, D-AUTH-007).
 */
enum class LimitReservationState { RESERVED, CONSUMED, RELEASED, FORCED }

/** FORCED 점유를 기록할 때의 일 한도 초과 표시(D-AUTH-010 "초과 사실 표시"). 정책을 알 수 없으면 UNDETERMINED. */
enum class LimitExcess { WITHIN, EXCEEDED, UNDETERMINED }

/** 승인 operation 하나의 일 한도 점유(값). [excess]는 FORCED 점유에만 있다. */
data class LimitReservation(
    val authorizationId: AuthorizationId,
    val amountMinor: Long,
    val state: LimitReservationState,
    val excess: LimitExcess? = null,
) {
    init {
        require(amountMinor >= 1) { "reserved amount must be positive" }
        require((state == LimitReservationState.FORCED) == (excess != null)) { "excess is recorded exactly for FORCED reservations" }
    }
}

/**
 * 범위·업무일당 일 한도 사용량(PAYMENT 소유 — AUTH-CONTRACT-001 §4.5). 권위 상태는 이 기록이며 캐시·Redis 카운터가 대신하지 않는다
 * (원칙 §5). 동시 점유의 직렬화는 행 잠금(AUTH-001)의 책임이고 domain은 합계 불변식만 지킨다:
 * RESERVED + CONSUMED + FORCED 합 + 새 금액 ≤ 일 한도(Limited일 때). FORCED는 한도를 넘어도 기록되므로 합이 한도를 넘을 수 있다(그동안 새 승인은
 * 거절된다 — D-AUTH-010).
 * 취소·환불은 일 한도를 복원하지 않는다(D-AUTH-007) — CONSUMED는 되돌리지 않는다.
 */
class DailyLimitUsage private constructor(val key: LimitUsageKey) {

    private val entries = linkedMapOf<AuthorizationId, LimitReservation>()

    val reservations: List<LimitReservation>
        get() = entries.values.toList()

    /** 새 승인의 한도 판정에 쓰는 점유 금액(RESERVED + CONSUMED + FORCED, D-AUTH-010). */
    val occupiedMinor: Long
        get() = sumOf(LimitReservationState.RESERVED, LimitReservationState.CONSUMED, LimitReservationState.FORCED)

    /** 그중 늦은 승인으로 다시 기록한 금액(FORCED — [occupiedMinor]에 포함된 부분). */
    val forcedMinor: Long
        get() = sumOf(LimitReservationState.FORCED)

    fun reservationOf(authorizationId: AuthorizationId): LimitReservation? = entries[authorizationId]

    /** [money]를 점유할 수 없는 이유(없으면 null). 건당 → 일 순서. 상태를 바꾸지 않는다. */
    fun rejectionFor(money: Money, policy: TransactionLimitPolicy): LimitRejection? {
        requireMatches(money, policy)
        val perTransaction = policy.perTransaction
        if (perTransaction is LimitAmount.Limited && money.amountMinor > perTransaction.amountMinor) {
            return LimitRejection.PER_TRANSACTION_LIMIT_EXCEEDED
        }
        val daily = policy.daily
        if (daily is LimitAmount.Limited && Math.addExact(occupiedMinor, money.amountMinor) > daily.amountMinor) {
            return LimitRejection.DAILY_LIMIT_EXCEEDED
        }
        return null
    }

    /** 점유한다. main 코드에서는 [AuthorizationStart]만 부른다(ArchUnit). 한도를 넘으면 [LimitExceededException]. */
    fun reserve(authorizationId: AuthorizationId, money: Money, policy: TransactionLimitPolicy) {
        check(authorizationId !in entries) { "authorization ${authorizationId.value} already reserved" }
        rejectionFor(money, policy)?.let { throw LimitExceededException(it) }
        entries[authorizationId] = LimitReservation(authorizationId, money.amountMinor, LimitReservationState.RESERVED)
    }

    /** 승인 확정 → 소비(멱등). 해제·강제 기록된 점유는 이 경로로 소비하지 않는다. */
    fun consume(authorizationId: AuthorizationId) {
        val r = entry(authorizationId)
        when (r.state) {
            LimitReservationState.CONSUMED -> return
            LimitReservationState.RELEASED, LimitReservationState.FORCED ->
                error("a ${r.state} reservation cannot be consumed by an authorization result: ${authorizationId.value}")
            LimitReservationState.RESERVED -> entries[authorizationId] = r.copy(state = LimitReservationState.CONSUMED)
        }
    }

    /** 무효과 확정(기관 거절·전송 전 실패·무효과) → 해제(멱등). 소비된 점유는 해제하지 않는다(취소 미복원, D-AUTH-007). */
    fun release(authorizationId: AuthorizationId) {
        val r = entry(authorizationId)
        when (r.state) {
            LimitReservationState.RELEASED -> return
            LimitReservationState.CONSUMED, LimitReservationState.FORCED ->
                error("a ${r.state} reservation is not released by an authorization result: ${authorizationId.value}")
            LimitReservationState.RESERVED -> entries[authorizationId] = r.copy(state = LimitReservationState.RELEASED)
        }
    }

    /**
     * 해제된 점유에 늦은 승인이 왔다(D-AUTH-010): RELEASED → FORCED로 다시 기록한다(새 승인 판정 합에 포함). 한도를 넘었다는 이유로 거부하지
     * 않고 기록하며 [policy]로 초과 여부를 표시한다
     * (null = 적용 정책을 알 수 없음 → UNDETERMINED). 이미 RESERVED·CONSUMED·FORCED면 효과 없음(이미 기록됨).
     */
    fun reoccupyAfterLateApproval(authorizationId: AuthorizationId, policy: TransactionLimitPolicy?) {
        val r = entry(authorizationId)
        if (r.state != LimitReservationState.RELEASED) return
        val excess = when {
            policy == null -> LimitExcess.UNDETERMINED
            else -> {
                require(policy.merchantAccountId == key.merchantAccountId && policy.currency == key.currency) {
                    "limit policy scope does not match the usage key"
                }
                when (val daily = policy.daily) {
                    LimitAmount.Unlimited -> LimitExcess.WITHIN
                    is LimitAmount.Limited -> {
                        val recorded = Math.addExact(occupiedMinor, r.amountMinor)
                        if (recorded > daily.amountMinor) LimitExcess.EXCEEDED else LimitExcess.WITHIN
                    }
                }
            }
        }
        entries[authorizationId] = r.copy(state = LimitReservationState.FORCED, excess = excess)
    }

    /**
     * hold 해제 판정으로 FORCED 점유를 정리한다(D-AUTH-010·011): NO_EFFECT_CONFIRMED → RELEASED, REAL_APPROVAL_CONFIRMED·
     * DOUBLE_APPROVAL_CORRECTED → CONSUMED(실제 승인이었으므로 일 승인 총액에 남는다 — 취소 미복원). FORCED가 아니면 효과 없음.
     */
    fun settleForced(authorizationId: AuthorizationId, finding: HoldReleaseFinding) {
        val r = entry(authorizationId)
        if (r.state != LimitReservationState.FORCED) return
        val next = when (finding) {
            HoldReleaseFinding.NO_EFFECT_CONFIRMED -> LimitReservationState.RELEASED
            HoldReleaseFinding.REAL_APPROVAL_CONFIRMED, HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED -> LimitReservationState.CONSUMED
        }
        entries[authorizationId] = r.copy(state = next, excess = null)
    }

    private fun entry(authorizationId: AuthorizationId): LimitReservation =
        entries[authorizationId] ?: error("no reservation for ${authorizationId.value}")

    private fun sumOf(vararg states: LimitReservationState): Long =
        entries.values.filter { it.state in states }.fold(0L) { sum, r -> Math.addExact(sum, r.amountMinor) }

    private fun requireMatches(money: Money, policy: TransactionLimitPolicy) {
        require(policy.merchantAccountId == key.merchantAccountId && policy.currency == key.currency) {
            "limit policy scope does not match the usage key"
        }
        require(money.currency == key.currency) { "money currency does not match the usage key" }
    }

    override fun toString(): String = "DailyLimitUsage(key=$key, reservations=${entries.size})"

    companion object {
        /** 아직 점유가 없는 범위·업무일의 사용량. */
        fun open(key: LimitUsageKey): DailyLimitUsage = DailyLimitUsage(key)

        /** 영속 복원(persistence Mapper 전용, AUTH-001). */
        fun reconstitute(key: LimitUsageKey, reservations: List<LimitReservation>): DailyLimitUsage {
            require(reservations.map { it.authorizationId }.toSet().size == reservations.size) { "one reservation per authorization" }
            return DailyLimitUsage(key).also { u -> reservations.forEach { u.entries[it.authorizationId] = it } }
        }
    }
}
