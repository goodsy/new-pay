package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZED
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZING
import com.paycore.payment.payment.domain.PaymentStatus.EXPIRED
import com.paycore.payment.payment.domain.PaymentStatus.FAILED
import com.paycore.payment.payment.domain.PaymentStatus.REQUESTED
import com.paycore.payment.payment.domain.PaymentStatus.UNKNOWN

/**
 * 같은 주문의 새 Payment 판정 결과(구현 계약 §4 표).
 *
 * 같은 멱등키 재요청(과거 결과 반환)은 application/idempotency 범위(CP-API)라 여기서 다루지 않는다. 여기서 "새 Payment"는 새 요청키의
 * 새 고객 시도다. 오류 코드·HTTP 매핑은 CP-CONTRACT-001에서 정한다.
 */
sealed interface PaymentSlotDecision {
    /** 새 REQUESTED Payment를 [nextSlotGeneration]으로 만들고 슬롯을 점유한다(control이 없으면 1). */
    data class CreateNewPayment(val nextSlotGeneration: Long) : PaymentSlotDecision

    /** 같은 고정 입력의 활성 시도(REQUESTED·AUTHORIZING·UNKNOWN)를 재사용한다. 새 Payment 없음. */
    data class UseExistingPayment(val paymentId: PaymentId, val status: PaymentStatus) : PaymentSlotDecision

    /** 주문의 고정 입력과 다르다. 상태와 무관하게 거절하며 변경 주문은 새 주문 ID를 써야 한다. */
    data class FixedIntentConflict(val existingPaymentId: PaymentId) : PaymentSlotDecision

    /** 새 Payment 금지. [referencePaymentId]는 확인·조회할 기존 Payment다. */
    data class Blocked(val reason: BlockReason, val referencePaymentId: PaymentId) : PaymentSlotDecision
}

enum class BlockReason {
    /**
     * 늦은 성공 등 상충 결과로 주문이 사건 보류 중이다. 조사·대사가 필요하다(해제 규칙은 미정). 현재 시도가 활성(REQUESTED 등)이어도
     * 같은 입력 재요청은 기존 시도 재사용이 아니라 이 차단으로 응답하고 기존 paymentId를 참조로 담는다(D-CPD-004, 사용자 확정 2026-10-06).
     */
    INCIDENT_HOLD,

    /** 과거 승인 성공이 있다. 취소/환불 여부와 무관하게 이 주문은 다시 승인하지 않으며 재구매는 새 주문 ID다. */
    AUTHORIZED_NEW_ORDER_REQUIRED,

    /** 현재 Payment가 FAILED/EXPIRED지만 D-CPD-002 충분 근거가 없다(근거 없음·timeout·NOT_FOUND·lease 만료). 확인/복구가 먼저다. */
    TERMINATION_EVIDENCE_INSUFFICIENT,
}

enum class IncidentReason {
    /** 확정 거절/만료로 종료된 Payment에 대해 승인 성공이 보고됐다(계약 가정 위반 사건, 확정 문서 §4). */
    SUCCESS_AFTER_TERMINATION,
}

/** 주문 사건 보류 기록(append-only). 자동 보상취소와 보류 해제 규칙은 별도 정책이라 여기 없다. */
data class IncidentHold(val paymentId: PaymentId, val reason: IncidentReason)

/**
 * 주문(OrderKey)당 하나의 결제 슬롯 통제(Aggregate Root). 가맹점 상품 주문 전체가 아니라 PG가 같은 주문의 결제 시도를 직렬화하는
 * 기록이다. Payment와 다른 Root지만 같은 PAYMENT DB의 같은 로컬 TX에서 함께 저장한다(구현 계약 §2·§5, 영속화는 CP-DB).
 *
 * 규칙(확정 문서 §1~§4, 구현 계약 §4, D-CPD-002):
 * - REQUESTED를 포함한 활성 Payment는 [currentPaymentId] 하나다. 새 Payment는 [occupy]로만 슬롯을 차지한다.
 * - 고정 입력([fixedIntent])은 주문 생성 때 정해지고 바뀌지 않는다. 다르면 상태와 무관하게 충돌이다.
 * - 과거 승인 성공([authorizedPaymentId])이 있으면 전액 반환 뒤에도 새 Payment를 만들지 않는다.
 * - 현재 Payment가 FAILED/EXPIRED이고 D-CPD-002 충분 근거가 있을 때만 새 Payment를 허용한다.
 * - incident hold가 있으면 새 Payment·승인 시작을 막는다.
 * - 슬롯을 바꾸거나 판정할 때 Payment는 [currentPaymentId]와 [generation]이 모두 맞아야 한다(오래된 결과·판정은 [StaleSlotException]).
 *   슬롯의 "해제"는 현재 Payment의 안전 종료 근거 기록이며 오래된 Payment의 결과는 현재 점유를 해제하지 못한다.
 *
 * 낙관적 잠금 version·행 잠금·UNIQUE는 persistence adapter(CP-DB) 책임이며 domain은 [generation] 의미만 가진다.
 */
class OrderPaymentControl private constructor(
    val orderKey: OrderKey,
    val fixedIntent: OrderIntentFingerprint,
    currentPaymentId: PaymentId,
    generation: Long,
) {
    var currentPaymentId: PaymentId = currentPaymentId
        private set

    var generation: Long = generation
        private set

    var authorizedPaymentId: PaymentId? = null
        private set

    private val holds = mutableListOf<IncidentHold>()

    /** 사건 보류 기록(append-only, 읽기 전용 사본). */
    val incidentHolds: List<IncidentHold>
        get() = holds.toList()

    fun isOnIncidentHold(): Boolean = holds.isNotEmpty()

    /**
     * 같은 주문의 새 요청(새 요청키)을 판정한다. [current]는 이 control의 현재 Payment여야 한다.
     * 우선순위: 고정 입력 불일치 → incident hold(D-CPD-004) → 과거 승인 성공 → 현재 상태·근거(D-CPD-002, 교차 근거 불인정 D-CPD-003).
     */
    fun decide(requestedIntent: OrderIntentFingerprint, current: Payment): PaymentSlotDecision {
        ensureCurrent(current)
        if (requestedIntent != fixedIntent) return PaymentSlotDecision.FixedIntentConflict(currentPaymentId)
        if (isOnIncidentHold()) return PaymentSlotDecision.Blocked(BlockReason.INCIDENT_HOLD, currentPaymentId)
        val authorized = authorizedPaymentId
        if (authorized != null || current.status == AUTHORIZED) {
            return PaymentSlotDecision.Blocked(BlockReason.AUTHORIZED_NEW_ORDER_REQUIRED, authorized ?: current.id)
        }
        return when (current.status) {
            REQUESTED, AUTHORIZING, UNKNOWN -> PaymentSlotDecision.UseExistingPayment(current.id, current.status)
            FAILED, EXPIRED ->
                if (current.hasSafeTermination()) {
                    PaymentSlotDecision.CreateNewPayment(generation + 1)
                } else {
                    PaymentSlotDecision.Blocked(BlockReason.TERMINATION_EVIDENCE_INSUFFICIENT, current.id)
                }
            AUTHORIZED -> error("unreachable: AUTHORIZED handled above")
        }
    }

    /**
     * 새 REQUESTED Payment로 슬롯을 점유한다. [newPayment]의 slotGeneration은 현재 generation + 1이어야 하고(오래된 판정 거부),
     * [current]에 대한 판정이 [PaymentSlotDecision.CreateNewPayment]여야 한다.
     * [newPayment]의 id는 이 주문이 아는 Payment ID(현재 점유·과거 승인 성공·사건 보류 대상)와 달라야 한다([PaymentIdReuseException]).
     * control은 과거 Payment 전체 목록을 갖지 않으므로 그 밖의 과거·다른 주문 ID 중복은 영속 PK(CP-DB-001)가 막는다.
     */
    fun occupy(newPayment: Payment, current: Payment) {
        require(newPayment.orderKey == orderKey) { "payment ${newPayment.id.value} belongs to another order" }
        require(newPayment.status == REQUESTED) { "a new payment must be REQUESTED: ${newPayment.status}" }
        if (newPayment.id in knownPaymentIds()) throw PaymentIdReuseException(orderKey, newPayment.id)
        if (newPayment.slotGeneration != generation + 1) {
            throw StaleSlotException(orderKey, currentPaymentId, generation, newPayment.id, newPayment.slotGeneration - 1)
        }
        val decision = decide(newPayment.intent, current)
        if (decision !is PaymentSlotDecision.CreateNewPayment) throw SlotOccupationRejectedException(decision)
        currentPaymentId = newPayment.id
        generation = newPayment.slotGeneration
    }

    /**
     * 현재 Payment의 승인 시작 전 검사(같은 슬롯에서 만료·종료와 직렬화한다 — 잠금은 CP-DB). REQUESTED가 아니면 Payment 전이 오류,
     * incident hold·과거 승인 성공이면 [AuthorizationStartRejectedException].
     */
    fun checkAuthorizationStart(payment: Payment) {
        ensureCurrent(payment)
        if (payment.status != REQUESTED) throw InvalidPaymentTransitionException(payment.id, payment.status, "startAuthorization")
        if (isOnIncidentHold()) throw AuthorizationStartRejectedException(payment.id, BlockReason.INCIDENT_HOLD)
        if (authorizedPaymentId != null) throw AuthorizationStartRejectedException(payment.id, BlockReason.AUTHORIZED_NEW_ORDER_REQUIRED)
    }

    /**
     * 현재 Payment의 승인 성공을 주문 성공 이력으로 남긴다(같은 로컬 TX). incident hold 중이어도 금융 사실은 기록한다.
     * 한 번 기록한 성공 이력은 지우지 않는다.
     */
    fun recordAuthorized(payment: Payment) {
        ensureCurrent(payment)
        require(payment.status == AUTHORIZED) { "payment ${payment.id.value} is not AUTHORIZED: ${payment.status}" }
        val existing = authorizedPaymentId
        check(existing == null || existing == payment.id) { "order already has authorized payment ${existing?.value}" }
        authorizedPaymentId = payment.id
    }

    /**
     * 확정 거절/만료로 종료된 이 주문의 Payment(현재 또는 과거 generation)에 대해 승인 성공이 보고됐다. Payment 상태는 덮어쓰지 않고
     * 주문을 사건 보류한다. 오래된 generation이어도 보류는 적용한다(보류는 슬롯을 해제하지 않고 막기만 한다).
     *
     * 멱등: 같은 늦은 성공이 응답·통보·조회로 여러 번 보고돼도 (paymentId, reason)당 보류는 하나다(이미 있으면 no-op).
     * 영속 유일성은 CP-DB-001의 UNIQUE(orderKey, paymentId, reason)가 보장한다.
     */
    fun reportSuccessAfterTermination(payment: Payment) {
        require(payment.orderKey == orderKey) { "payment ${payment.id.value} belongs to another order" }
        require(payment.slotGeneration <= generation) { "payment ${payment.id.value} is from a future generation" }
        require(payment.status == FAILED || payment.status == EXPIRED) {
            "success after termination needs a FAILED/EXPIRED payment: ${payment.status}"
        }
        val hold = IncidentHold(payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION)
        if (hold !in holds) holds += hold
    }

    private fun knownPaymentIds(): Set<PaymentId> =
        buildSet {
            add(currentPaymentId)
            authorizedPaymentId?.let(::add)
            holds.mapTo(this) { it.paymentId }
        }

    private fun ensureCurrent(payment: Payment) {
        if (payment.id != currentPaymentId || payment.slotGeneration != generation || payment.orderKey != orderKey) {
            throw StaleSlotException(orderKey, currentPaymentId, generation, payment.id, payment.slotGeneration)
        }
    }

    override fun toString(): String =
        "OrderPaymentControl(orderKey=$orderKey, current=${currentPaymentId.value}@$generation, " +
            "authorized=${authorizedPaymentId?.value}, holds=${holds.size})"

    companion object {
        /** control이 아직 없는 주문의 판정: 첫 Payment를 generation 1로 만든다. */
        fun decideForNewOrder(): PaymentSlotDecision.CreateNewPayment = PaymentSlotDecision.CreateNewPayment(1)

        /** 첫 Payment(REQUESTED, generation 1)로 주문 슬롯을 연다. 고정 입력은 첫 Payment의 fingerprint로 정해진다. */
        fun open(firstPayment: Payment): OrderPaymentControl {
            require(firstPayment.status == REQUESTED) { "first payment must be REQUESTED: ${firstPayment.status}" }
            require(firstPayment.slotGeneration == 1L) { "first payment must be generation 1: ${firstPayment.slotGeneration}" }
            return OrderPaymentControl(firstPayment.orderKey, firstPayment.intent, firstPayment.id, 1)
        }
    }
}
