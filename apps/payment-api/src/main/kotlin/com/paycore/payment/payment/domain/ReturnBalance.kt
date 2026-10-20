package com.paycore.payment.payment.domain

/**
 * 반환 예약 상태(CANCEL-CONTRACT-001 §4.3, D-CANCEL-006):
 * - RESERVED →(기관 확정 취소 성공) CONSUMED, RESERVED →(기관 확정 거절·전송 전 실패 확정·무효과 확정) RELEASED. 결과 불명확(OPEN·UNKNOWN)은 RESERVED 유지.
 * - RELEASED →(같은 취소의 늦은 성공) FORCED: 실제 반환이 있었을 수 있는 금액을 반환 가능 금액에서 뺀다. 승인액을 넘어도 기록하고 초과를 표시한다.
 *   FORCED의 출구(해제 판정별 RELEASED/CONSUMED)는 RECON-CONTRACT-001이 정한다 — 그 전까지 FORCED는 유지된다(fail-closed).
 */
enum class ReturnReservationState { RESERVED, CONSUMED, RELEASED, FORCED }

/** FORCED 재기록 시점의 승인액 초과 표시(D-CANCEL-006 — 초과 가능이면 RECON Case CRITICAL 힌트). */
enum class ReturnExcess { WITHIN, EXCEEDED }

/** 반환 요약(Payment 생명주기와 별개 — K-17). 새 승인 허용 판단에 쓰지 않는다(K-12: 전액 반환 뒤에도 재승인 금지). */
enum class ReturnStatus { NONE, PARTIALLY_CANCELLED, FULLY_CANCELLED }

/**
 * 취소 operation 하나의 반환 예약(값). [excess]는 FORCED에만 있다. [sequence]는 CONSUMED에만 있는 Payment 안 반환 완료 순번(1부터 — PaymentCancelled의
 * 반환 순번, CC-08·D-CANCEL-008 누적 비례 배분 입력).
 */
data class ReturnReservation(
    val cancellationId: CancellationId,
    val amountMinor: Long,
    val state: ReturnReservationState,
    val excess: ReturnExcess? = null,
    val sequence: Int? = null,
) {
    init {
        require(amountMinor >= 1) { "reserved return amount must be positive" }
        require((state == ReturnReservationState.FORCED) == (excess != null)) { "excess is recorded exactly for FORCED reservations" }
        require((state == ReturnReservationState.CONSUMED) == (sequence != null)) { "a completion sequence is recorded exactly for CONSUMED reservations" }
        require(sequence == null || sequence >= 1) { "completion sequence must be positive" }
    }
}

/** 완료된 반환 하나의 순번과 그 시점 누적 반환액(PaymentCancelled payload — CC-08). */
data class ReturnCompletion(val sequence: Int, val cumulativeMinor: Long)

/**
 * 반환 가능 금액 통제(Aggregate Root, Payment 1:1 — CANCEL-CONTRACT-001 §4.3, CC-02). 권위 상태는 이 기록이며 캐시·Redis가 대신하지 않는다(원칙 §5).
 * 동시 예약의 직렬화는 행 잠금(CANCEL-001)의 책임이고 domain은 합계 불변식을 지킨다(review H-1 — FORCED는 따로 합산):
 *
 * ```
 * INV-C1  completed + reserved ≤ authorized                       (항상 — DB CHECK와 같은 식)
 *         cancellable = max(0, authorized − completed − reserved − forced)
 *         excess      = max(0, completed + reserved + forced − authorized)   (FORCED에서만 생긴다)
 *         진행 중(RESERVED) 반환은 Payment당 최대 1건(D-CANCEL-003)
 * INV-C8  status FULLY_CANCELLED ⇔ completed = authorized
 * ```
 *
 * [revision]은 이 잔액의 업무 변경마다 1씩 오르는 값이다 — 운영자 보정 취소의 "승인한 대상 버전" 대조(D-ADMIN-007, review m-3)에 쓴다(영속 JPA version과
 * 별개). 일 한도(D-AUTH-007)·주문 슬롯(K-12)과 무관하다. 금액 합은 `Math.addExact`(overflow는 결함). v1 반환 종류는 카드 취소뿐이다(D-CANCEL-007).
 */
class ReturnBalance private constructor(
    val paymentId: PaymentId,
    val authorizationId: AuthorizationId,
    val originalTransactionId: PaymentTransactionId,
    val authorized: Money,
) {
    private val entries = linkedMapOf<CancellationId, ReturnReservation>()

    var revision: Long = 0
        private set

    val reservations: List<ReturnReservation>
        get() = entries.values.toList()

    fun reservationOf(cancellationId: CancellationId): ReturnReservation? = entries[cancellationId]

    /** 완료된 반환 합(= CANCEL 거래 금액 합). */
    val completedMinor: Long
        get() = sumOf(ReturnReservationState.CONSUMED)

    /** 결과가 확정되지 않은 반환 예약 합(RESERVED). */
    val reservedMinor: Long
        get() = sumOf(ReturnReservationState.RESERVED)

    /** 늦은 취소 성공으로 다시 기록한 금액 합(FORCED — 실제 반환이 있었을 수 있음). */
    val forcedMinor: Long
        get() = sumOf(ReturnReservationState.FORCED)

    private val totalMinor: Long
        get() = Math.addExact(Math.addExact(completedMinor, reservedMinor), forcedMinor)

    /** 지금 새로 반환할 수 있는 금액(0 이상). */
    val cancellableMinor: Long
        get() = cancellableMinor(authorized.amountMinor, completedMinor, reservedMinor, forcedMinor)

    /** 승인액을 넘은 반환 가능성(FORCED에서만 생긴다). 0이면 초과 없음. */
    val excessMinor: Long
        get() = maxOf(0L, totalMinor - authorized.amountMinor)

    val status: ReturnStatus
        get() = returnStatus(authorized.amountMinor, completedMinor)

    /** 결과가 확정되지 않은 반환(RESERVED)이 있다(D-CANCEL-003). */
    fun hasInFlight(): Boolean = entries.values.any { it.state == ReturnReservationState.RESERVED }

    /** 완료된 반환의 순번과 그 시점 누적 반환액. 완료되지 않았으면 null. */
    fun completionOf(cancellationId: CancellationId): ReturnCompletion? {
        val seq = entries[cancellationId]?.sequence ?: return null
        val cumulative = entries.values.filter { it.sequence != null && it.sequence <= seq }.fold(0L) { acc, r -> Math.addExact(acc, r.amountMinor) }
        return ReturnCompletion(seq, cumulative)
    }

    /**
     * 반환 예약(TX1). main 코드에서는 [CancelStart]만 부른다(ArchUnit). 업무 거절(진행 중·금액 초과·통화)은 [CancelStart]가 먼저 판정하므로 여기서 걸리면
     * 호출 결함이다.
     */
    fun reserve(cancellationId: CancellationId, money: Money) {
        require(cancellationId !in entries) { "cancellation id is already reserved" }
        require(money.currency == authorized.currency) { "return currency must be the original currency" }
        check(!hasInFlight()) { "another return is in flight on this payment" }
        require(money.amountMinor <= cancellableMinor) { "return exceeds the cancellable amount" }
        put(ReturnReservation(cancellationId, money.amountMinor, ReturnReservationState.RESERVED))
    }

    /** 기관 확정 취소 성공 → 소비(멱등) + 완료 순번 부여. 해제·FORCED 예약은 소비하지 않는다. */
    internal fun consume(cancellationId: CancellationId) {
        val r = entry(cancellationId)
        when (r.state) {
            ReturnReservationState.CONSUMED -> return
            ReturnReservationState.RESERVED -> {
                val next = (entries.values.mapNotNull { it.sequence }.maxOrNull() ?: 0) + 1
                put(r.copy(state = ReturnReservationState.CONSUMED, sequence = next))
            }
            ReturnReservationState.RELEASED, ReturnReservationState.FORCED -> throw IllegalStateException("a ${r.state} return cannot be consumed")
        }
    }

    /** 무효과 확정(기관 거절·전송 전 실패·무효과) → 해제(멱등). 소비·FORCED 반환은 해제하지 않는다. */
    internal fun release(cancellationId: CancellationId) {
        val r = entry(cancellationId)
        when (r.state) {
            ReturnReservationState.RELEASED -> return
            ReturnReservationState.RESERVED -> put(r.copy(state = ReturnReservationState.RELEASED))
            ReturnReservationState.CONSUMED, ReturnReservationState.FORCED -> throw IllegalStateException("a ${r.state} return cannot be released")
        }
    }

    /**
     * 해제한 반환에 늦은 취소 성공(D-CANCEL-006): RELEASED → FORCED. 승인액을 넘어도 기록하고 초과 여부를 표시한다(초과 = 기존 완료 + 예약 + FORCED + 이 금액 >
     * 승인액). RELEASED가 아니면 효과 없음(멱등).
     */
    internal fun reoccupyAfterLateCancel(cancellationId: CancellationId) {
        val r = entry(cancellationId)
        if (r.state != ReturnReservationState.RELEASED) return
        val excess = if (Math.addExact(totalMinor, r.amountMinor) > authorized.amountMinor) ReturnExcess.EXCEEDED else ReturnExcess.WITHIN
        put(r.copy(state = ReturnReservationState.FORCED, excess = excess))
    }

    private fun put(r: ReturnReservation) {
        entries[r.cancellationId] = r
        revision++
    }

    private fun entry(cancellationId: CancellationId): ReturnReservation =
        entries[cancellationId] ?: throw IllegalArgumentException("no return reservation for this cancellation")

    private fun sumOf(state: ReturnReservationState): Long =
        entries.values.filter { it.state == state }.fold(0L) { acc, r -> Math.addExact(acc, r.amountMinor) }

    override fun toString(): String = "ReturnBalance(payment=${paymentId.value}, reservations=${entries.size}, revision=$revision)"

    companion object {
        /**
         * 반환 가능 금액 식(INV-C1): `max(0, authorized − completed − reserved − forced)`. 조회 view(취소 응답·GetPayment)도 저장 합계 열로 이 식만 쓴다
         * (CP-QUERY-001 F-1 — FORCED 초과가 있어도 음수가 아니다). 합은 `Math.addExact`(overflow는 결함).
         */
        fun cancellableMinor(authorizedMinor: Long, completedMinor: Long, reservedMinor: Long, forcedMinor: Long): Long =
            maxOf(0L, authorizedMinor - Math.addExact(Math.addExact(completedMinor, reservedMinor), forcedMinor))

        /** 반환 상태 식(INV-C8): 완료 반환 = 승인액이면 FULLY_CANCELLED, 0보다 크면 PARTIALLY_CANCELLED, 아니면 NONE. */
        fun returnStatus(authorizedMinor: Long, completedMinor: Long): ReturnStatus = when {
            completedMinor == authorizedMinor -> ReturnStatus.FULLY_CANCELLED
            completedMinor > 0 -> ReturnStatus.PARTIALLY_CANCELLED
            else -> ReturnStatus.NONE
        }

        /**
         * 확정 승인 거래가 있는 Authorization에서 연다(첫 취소 시작 — CC-02 지연 개설). revision 0. AUTHORIZED 또는 RECON이 늦은 승인 거래를 기록한 FAILED
         * (D-CANCEL-011 — 어느 개시자가 열 수 있는지는 [CancelStart]가 판정, main 호출자는 CancelStart만 — ArchUnit).
         */
        fun open(authorization: PaymentAuthorization): ReturnBalance {
            val tx = authorization.transaction
            require(tx != null) { "a return balance needs a confirmed authorization transaction" }
            return ReturnBalance(authorization.paymentId, authorization.id, tx.id, tx.money)
        }

        /**
         * 영속 복원(persistence Mapper 전용 — ArchUnit). domain에서 존재할 수 없는 행은 거부한다(조용히 고치지 않는다): 예약 ID 유일, RESERVED 최대 1건, 완료 순번 1..n,
         * revision ≥ 업무 변경 이벤트 수, 초과가 있으면 EXCEEDED FORCED 존재,
         * INV-C1 completed + reserved ≤ authorized(초과는 FORCED에서만 허용), 합계 overflow는 결함([ArithmeticException]).
         */
        fun reconstitute(
            paymentId: PaymentId,
            authorizationId: AuthorizationId,
            originalTransactionId: PaymentTransactionId,
            authorized: Money,
            reservations: List<ReturnReservation>,
            revision: Long,
        ): ReturnBalance {
            require(reservations.map { it.cancellationId }.toSet().size == reservations.size) { "return reservations must be unique per cancellation" }
            require(reservations.count { it.state == ReturnReservationState.RESERVED } <= 1) { "at most one return is in flight" }
            require(reservations.mapNotNull { it.sequence }.sorted() == (1..reservations.count { it.sequence != null }).toList()) {
                "completion sequences must be 1..n"
            }
            // review n-2: 업무 변경 이벤트 수(RESERVED 1, CONSUMED·RELEASED 2, FORCED 3) 이상이어야 한다.
            val events = reservations.sumOf {
                when (it.state) {
                    ReturnReservationState.RESERVED -> 1L
                    ReturnReservationState.CONSUMED, ReturnReservationState.RELEASED -> 2L
                    ReturnReservationState.FORCED -> 3L
                }
            }
            require(revision >= events) { "revision cannot be lower than the number of balance-changing events" }
            val b = ReturnBalance(paymentId, authorizationId, originalTransactionId, authorized)
            reservations.forEach { b.entries[it.cancellationId] = it }
            b.revision = revision
            b.totalMinor // FORCED 포함 합계 overflow도 결함으로 드러낸다
            require(Math.addExact(b.completedMinor, b.reservedMinor) <= authorized.amountMinor) {
                "completed and in-flight returns exceed the authorized amount"
            }
            // review n-2: 초과는 FORCED 재기록에서만 생기므로, 초과가 있으면 그것을 설명하는 EXCEEDED FORCED가 있어야 한다.
            require(b.excessMinor == 0L || reservations.any { it.excess == ReturnExcess.EXCEEDED }) { "a stored excess needs an EXCEEDED forced return" }
            return b
        }
    }
}
