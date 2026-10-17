package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZED
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZING
import com.paycore.payment.payment.domain.PaymentStatus.EXPIRED
import com.paycore.payment.payment.domain.PaymentStatus.FAILED
import com.paycore.payment.payment.domain.PaymentStatus.REQUESTED
import com.paycore.payment.payment.domain.PaymentStatus.UNKNOWN

/**
 * 고객의 한 결제 시도(Aggregate Root). 주문 하나에 Payment 이력은 1:N이며 새 시도는 새 Payment다(확정 문서 §1).
 *
 * 이 작업(CP-DOMAIN-001)은 주문 활성 규칙에 필요한 상태와 확정 불변식만 표현한다. 금액·통화·Site·수단, 승인 Attempt·기관 결과
 * 해석·취소/환불은 CP-CONTRACT/AUTH 범위이며 여기 두지 않는다.
 *
 * 불변식:
 * - 상태 전이는 REQUESTED → AUTHORIZING → (UNKNOWN →) AUTHORIZED / FAILED / EXPIRED. AUTHORIZED·FAILED·EXPIRED는 종료 상태라
 *   늦은 결과가 덮어쓰지 않는다(늦은 성공은 OrderPaymentControl의 incident hold로 처리).
 * - 이미 전송했거나 전송 여부가 불명확한 시도(AUTHORIZING·UNKNOWN)는 시간 경과·조회 NOT_FOUND만으로 FAILED/EXPIRED가 되지 않는다:
 *   목표 상태에 충분한 D-CPD-002 근거가 있어야 종료한다(확정 문서 §3).
 * - REQUESTED는 아직 승인을 시작하지 않아 미전송이므로 결제창 만료·종료가 가능하다. 이때 근거는 주어진 대로 기록하고, 새 Payment
 *   허용 여부는 근거로 따로 판단한다([hasSafeTermination]). 주의: REQUESTED 종료 근거의 출처(예: 미전송인데 기관 확정 거절)는 지금
 *   검증하지 않는다 — Attempt 전송 상태와 함께 AUTH-001에서 제한한다. "REQUESTED = 미전송" 불변식은 AUTH-001에서 Attempt 생성과
 *   AUTHORIZING 전이를 같은 TX에 두어야 성립한다.
 * - 종료 근거는 append-only다.
 *
 * [slotGeneration]은 이 Payment가 주문 슬롯을 점유한 generation이다. OrderPaymentControl은 currentPaymentId와 함께 이 값을 비교해
 * 오래된 결과가 새 점유를 바꾸지 못하게 한다.
 */
class Payment private constructor(
    val id: PaymentId,
    val orderKey: OrderKey,
    val intent: OrderIntentFingerprint,
    val slotGeneration: Long,
) {
    var status: PaymentStatus = REQUESTED
        private set

    private val evidence = mutableListOf<TerminationEvidence>()

    /**
     * 결제창 절대 만료 시각(D-AUTHN-005). 새 Payment는 생성 때 고정하고 바꾸지 않는다. null = V7 이전 저장 행(D-AUTHN-007 — 채우기는 payment-api 기동 migration
     * 전략이 V8_1 전에 한다, AUTHN-001 §7.5.6 검토 #2 m-2. domain에는 채우기 연산이 없다).
     */
    var checkoutWindow: CheckoutWindow? = null
        private set

    /** 결제창 시각이 있고 `now ≥ expiresAt`이면 경과(AUTHN-001 §7.5). 시각이 없으면 경과로 보지 않는다(채우기 전 — 결함 방어는 [RequestedPaymentExpiry]). */
    fun isCheckoutWindowElapsed(now: java.time.Instant): Boolean = checkoutWindow?.let { !now.isBefore(it.expiresAt) } ?: false

    /** 기록된 종료 근거(append-only, 읽기 전용 사본). */
    val terminationEvidence: List<TerminationEvidence>
        get() = evidence.toList()

    /** 승인을 시작한다(외부 승인 Attempt 점유는 AUTH). 오래된/만료된 결제창은 시작할 수 없다. */
    fun startAuthorization() {
        transition("startAuthorization", from = setOf(REQUESTED), to = AUTHORIZING)
    }

    /** 승인 결과가 불명확하다(timeout·응답 유실 등). */
    fun markUnknown() {
        transition("markUnknown", from = setOf(AUTHORIZING), to = UNKNOWN)
    }

    /** 승인 성공. */
    fun markAuthorized() {
        transition("markAuthorized", from = setOf(AUTHORIZING, UNKNOWN), to = AUTHORIZED)
    }

    /** 실패로 종료한다. AUTHORIZING/UNKNOWN에서는 FAILED에 충분한 근거가 있어야 한다. */
    fun fail(evidence: TerminationEvidence?) {
        close("fail", FAILED, evidence)
    }

    /** 만료로 종료한다. AUTHORIZING/UNKNOWN에서는 EXPIRED에 충분한 근거가 있어야 한다. */
    fun expire(evidence: TerminationEvidence?) {
        close("expire", EXPIRED, evidence)
    }

    /** 종료된 Payment에 나중에 확인된 근거를 덧붙인다(append-only). */
    fun addTerminationEvidence(evidence: TerminationEvidence) {
        if (status != FAILED && status != EXPIRED) throw InvalidPaymentTransitionException(id, status, "addTerminationEvidence")
        this.evidence += evidence
    }

    /** FAILED/EXPIRED이고 그 상태에 충분한 D-CPD-002 근거가 하나라도 기록돼 있으면 새 Payment의 안전 종료 근거가 있다. */
    fun hasSafeTermination(): Boolean =
        (status == FAILED || status == EXPIRED) && evidence.any { it.isSufficientFor(status) }

    private fun close(action: String, target: PaymentStatus, evidence: TerminationEvidence?) {
        when (status) {
            REQUESTED -> {}
            AUTHORIZING, UNKNOWN ->
                if (evidence == null || !evidence.isSufficientFor(target)) {
                    throw InvalidPaymentTransitionException(id, status, "$action(${evidence ?: "NO_EVIDENCE"})")
                }
            AUTHORIZED, FAILED, EXPIRED -> throw InvalidPaymentTransitionException(id, status, action)
        }
        status = target
        if (evidence != null) this.evidence += evidence
    }

    private fun transition(action: String, from: Set<PaymentStatus>, to: PaymentStatus) {
        if (status !in from) throw InvalidPaymentTransitionException(id, status, action)
        status = to
    }

    override fun toString(): String = "Payment(id=${id.value}, status=$status, slotGeneration=$slotGeneration)"

    companion object {
        /**
         * 새 고객 시도(REQUESTED). id는 서버가 생성해 넘긴다. slotGeneration은 주문 슬롯 점유 판정이 정한 값이다. [checkoutWindow]는 생성 시각 + 버전 있는
         * 결제 유효기간 설정으로 application이 계산한 절대 만료 시각이다(D-AUTHN-005 — 재사용·재시도로 연장하지 않는다).
         */
        fun request(id: PaymentId, orderKey: OrderKey, intent: OrderIntentFingerprint, slotGeneration: Long, checkoutWindow: CheckoutWindow): Payment {
            require(slotGeneration >= 1) { "slotGeneration must be positive: $slotGeneration" }
            return Payment(id, orderKey, intent, slotGeneration).also { it.checkoutWindow = checkoutWindow }
        }

        /**
         * 영속 복원(persistence Mapper 전용, CP-DB-001). 새 시도를 만들지 않고 이미 저장된 상태를 되돌린다. 시험 편의 생성 API가 아니다:
         * Named Test Fixture는 [request]와 전이 메서드로 상태를 만든다(Test Fixture Policy).
         *
         * 저장 상태가 domain에서 존재할 수 없으면 거부한다(조용히 고치지 않는다): slotGeneration ≥ 1, 종료 근거는 FAILED/EXPIRED에만.
         * 어떤 전이 경로로 종료됐는지(REQUESTED 종료는 근거 불요)는 행에 없어 검사하지 않는다 — 그 검사는 전이 시점(종료 메서드)의 책임이다.
         */
        fun reconstitute(
            id: PaymentId,
            orderKey: OrderKey,
            intent: OrderIntentFingerprint,
            slotGeneration: Long,
            status: PaymentStatus,
            terminationEvidence: List<TerminationEvidence>,
            checkoutWindow: CheckoutWindow?,
        ): Payment {
            require(slotGeneration >= 1) { "slotGeneration must be positive: $slotGeneration" }
            require(terminationEvidence.isEmpty() || status == FAILED || status == EXPIRED) {
                "termination evidence is only stored for FAILED/EXPIRED payments: $status"
            }
            return Payment(id, orderKey, intent, slotGeneration).also {
                it.status = status
                it.evidence += terminationEvidence
                it.checkoutWindow = checkoutWindow
            }
        }
    }
}
