package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZED
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZING
import com.paycore.payment.payment.domain.PaymentStatus.EXPIRED
import com.paycore.payment.payment.domain.PaymentStatus.FAILED
import com.paycore.payment.payment.domain.PaymentStatus.REQUESTED
import com.paycore.payment.payment.domain.PaymentStatus.UNKNOWN

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

    private val releases = mutableListOf<IncidentHoldRelease>()

    /** 사건 보류 기록(append-only, 해제된 것 포함, 읽기 전용 사본). */
    val incidentHolds: List<IncidentHold>
        get() = holds.toList()

    /** 사건 보류 해제 기록(append-only, 읽기 전용 사본). */
    val incidentHoldReleases: List<IncidentHoldRelease>
        get() = releases.toList()

    /** 해제되지 않은 사건 보류. 하나를 해제해도 다른 보류는 남는다. */
    val activeIncidentHolds: List<IncidentHold>
        get() = holds.filter { hold -> releases.none { it.hold == hold } }

    fun isOnIncidentHold(): Boolean = activeIncidentHolds.isNotEmpty()

    fun hasActiveHold(paymentId: PaymentId, reason: IncidentReason): Boolean =
        activeIncidentHolds.any { it.paymentId == paymentId && it.reason == reason }

    /**
     * (paymentId, reason) 사건 보류를 연다. 이미 활성이면 효과 없음(멱등). 이전 보류가 모두 해제됐다면 해제 판정과 무관하게 **새 보류**
     * (occurrence + 1)를 기록하고 기존 보류는 다시 쓰지 않는다: NO_EFFECT 해제 뒤(D-AUTH-012), REAL_APPROVAL_CONFIRMED·DOUBLE_APPROVAL_CORRECTED
     * 해제 뒤(D-AUTH-013 — 해제 판정은 그때 확인한 승인만 설명하고 그 뒤 들어온 다른 승인·반대 결과는 설명하지 않는다). 사용자 확정 2026-10-15.
     * 호출자(Resolution)는 새 관찰 키의 증거에서만 부른다. 영속 UNIQUE(OrderKey, paymentId, reason)는 occurrence를 포함하도록 AUTH-001에서 바꾼다.
     * @return 호출 뒤 (paymentId, reason) 활성 보류가 있으면 true(항상 true — 호출자가 Incident·FORCED와 맞추는 데 쓴다).
     */
    private fun openHold(paymentId: PaymentId, reason: IncidentReason): Boolean {
        if (hasActiveHold(paymentId, reason)) return true
        val previous = holds.filter { it.paymentId == paymentId && it.reason == reason }
        if (previous.isEmpty()) {
            holds += IncidentHold(paymentId, reason, 1)
            return true
        }
        val last = previous.maxBy { it.occurrence }
        holds += IncidentHold(paymentId, reason, last.occurrence + 1)
        return true
    }

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
     * 현재 Payment의 승인을 시작한다(CP-DOMAIN-001 리뷰 L-2, CP-API-001): 슬롯 검사([checkAuthorizationStart])와 Payment 전이
     * (`REQUESTED → AUTHORIZING`)를 **한 연산**으로 묶어 incident hold·과거 승인·오래된 슬롯 검사를 건너뛴 승인 시작을 막는다.
     * 검사가 실패하면 Payment 상태는 바뀌지 않는다(전이 전에 예외). main 코드에서 `Payment.startAuthorization()`을 직접 부르는 것은
     * ArchUnit 규칙(paymentStartAuthorizationOnlyThroughControl)이 금지한다 — Kotlin에는 package-private가 없어 언어로 막을 수 없다.
     * 이 연산 자체도 main 코드에서는 [AuthorizationStart]만 부른다(ArchUnit controlStartAuthorizationOnlyThroughAuthorizationStart,
     * AUTH-CONTRACT-001): Attempt 생성·세션 귀속·한도 점유와 AUTHORIZING 전이를 한 domain 연산으로 묶는다(L-3).
     * 같은 슬롯의 만료·종료와의 직렬화(control 행 잠금)는 승인 시작 Use Case(AUTH-001)의 책임이다.
     */
    fun startAuthorization(payment: Payment) {
        checkAuthorizationStart(payment)
        payment.startAuthorization()
    }

    /**
     * 승인 시작 전 검사. REQUESTED가 아니면 Payment 전이 오류, incident hold·과거 승인 성공이면 [AuthorizationStartRejectedException],
     * 현재 슬롯이 아니면 [StaleSlotException]. 상태를 바꾸지 않는다. 단독 공개 연산이 아니다: [startAuthorization]과, 다른 Aggregate를
     * 바꾸기 전에 슬롯 거절을 먼저 판정해야 하는 [AuthorizationStart]만 부른다(모듈 internal).
     */
    internal fun checkAuthorizationStart(payment: Payment) {
        ensureCurrent(payment)
        if (payment.status != REQUESTED) throw InvalidPaymentTransitionException(payment.id, payment.status, "startAuthorization")
        if (isOnIncidentHold()) throw AuthorizationStartRejectedException(payment.id, BlockReason.INCIDENT_HOLD)
        if (authorizedPaymentId != null) throw AuthorizationStartRejectedException(payment.id, BlockReason.AUTHORIZED_NEW_ORDER_REQUIRED)
    }

    /**
     * 현재 Payment의 승인 확정을 한 연산으로 반영한다(AUTH-CONTRACT-001 §17-5): `Payment.markAuthorized()`와 주문 성공 이력 기록을
     * 함께 한다. 검사(현재 슬롯·AUTHORIZING/UNKNOWN)가 실패하면 아무것도 바뀌지 않는다. 주문에 다른 승인 성공 이력이 있으면 예외 대신
     * 이중 승인 상충 hold를 열고 true를 돌려준다(review round2 M-3). main 코드에서
     * `Payment.markAuthorized`·[recordAuthorized]·이 연산은 domain 안에서만 부른다(ArchUnit domainTransitionsOnlyInsideDomain).
     */
    fun completeAuthorization(payment: Payment): Boolean {
        ensureCurrent(payment)
        if (payment.status != AUTHORIZING && payment.status != UNKNOWN) {
            throw InvalidPaymentTransitionException(payment.id, payment.status, "completeAuthorization")
        }
        val existing = authorizedPaymentId
        payment.markAuthorized()
        if (existing == null || existing == payment.id) {
            authorizedPaymentId = payment.id
            return false
        }
        // 주문에 다른 승인 성공 이력이 이미 있다(예: 이전 generation 늦은 승인을 REAL로 해제한 뒤 현재 generation 승인 — review round2 M-3).
        // 기관 확정 효과이므로 기록하고(D-AUTH-003), 주문 승인 이력은 기존 값을 유지하며 이중 승인 상충 hold를 연다(D-AUTH-011과 같은 처리).
        // 이 hold는 DOUBLE_APPROVAL_CORRECTED로 해제한다.
        return openHold(payment.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT)
    }

    /**
     * 현재 Payment의 승인 성공을 주문 성공 이력으로 남긴다(같은 로컬 TX). incident hold 중이어도 금융 사실은 기록한다.
     * 한 번 기록한 성공 이력은 지우지 않는다. 승인 확정 경로는 [completeAuthorization]을 쓴다 — 이 연산은 모듈 internal이다(review n-5:
     * Payment 전이와 짝을 맞추지 않는 공개 경로를 없앤다. 같은 모듈의 시험 상태 준비만 쓴다).
     */
    internal fun recordAuthorized(payment: Payment) {
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
    fun reportSuccessAfterTermination(payment: Payment): Boolean {
        require(payment.orderKey == orderKey) { "payment ${payment.id.value} belongs to another order" }
        require(payment.slotGeneration <= generation) { "payment ${payment.id.value} is from a future generation" }
        require(payment.status == FAILED || payment.status == EXPIRED) {
            "success after termination needs a FAILED/EXPIRED payment: ${payment.status}"
        }
        return openHold(payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION)
    }

    /**
     * 이 주문의 Payment에 기관 상충 결과가 보고됐다(D-AUTH-002·D-AUTH-008). Payment 상태는 바꾸지 않고 주문을 사건 보류한다.
     * 대상은 승인을 시작한 Payment(AUTHORIZING·UNKNOWN·AUTHORIZED, 그리고 같은 관찰 키로 내용이 다른 결과가 온 FAILED — review m-1)다.
     * 멱등: (paymentId, reason)당 보류 하나.
     */
    fun reportConflictingResult(payment: Payment): Boolean {
        require(payment.orderKey == orderKey) { "payment ${payment.id.value} belongs to another order" }
        require(payment.slotGeneration <= generation) { "payment ${payment.id.value} is from a future generation" }
        require(payment.status == AUTHORIZING || payment.status == UNKNOWN || payment.status == AUTHORIZED || payment.status == FAILED) {
            "a conflicting institution result needs a payment whose authorization started: ${payment.status}"
        }
        return openHold(payment.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT)
    }

    /**
     * 이 주문의 확정 승인 거래가 있는 Payment(AUTHORIZED, 또는 D-CANCEL-011의 늦게 확정된 P1)에 취소 결과 사건이 보고됐다(D-CANCEL-006): 취소 결과 상충·늦은 취소 성공·같은 관찰 키 다른 내용. Payment·취소 상태는 바꾸지
     * 않고 그 Payment를 사건 보류한다(추가 가맹점 취소 차단 — [CancelStart]). 멱등: (paymentId, reason) 활성 보류 하나(해제가 아직 없으므로 occurrence 1).
     */
    fun reportCancellationResultConflict(payment: Payment): Boolean {
        require(payment.orderKey == orderKey) { "payment ${payment.id.value} belongs to another order" }
        // D-CANCEL-011: 취소는 AUTHORIZED Payment 또는 RECON이 늦은 승인 거래를 기록한 FAILED/EXPIRED P1(운영자 보정)에만 있다.
        require(payment.status == AUTHORIZED || payment.status == FAILED || payment.status == EXPIRED) {
            "a cancel result conflict needs a payment that can hold a confirmed authorization transaction: ${payment.status}"
        }
        return openHold(payment.id, IncidentReason.CANCEL_RESULT_CONFLICT)
    }

    /**
     * 사건 보류를 해제한다(D-AUTH-002: 2인 승인 + 재대사 MATCH — [IncidentHoldRelease] 생성자가 강제). 해제 기록은 append-only이고 보류
     * 기록은 남는다. [HoldReleaseFinding.REAL_APPROVAL_CONFIRMED]이면 그 Payment를 주문 승인 성공 이력으로 남긴다 — 실제 기관 승인이
     * 있는 주문은 취소 여부와 무관하게 새 Payment를 만들지 않는다(시도 §2, AUTH-CONTRACT-001 §17-1).
     * [HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED]는 주문에 이 hold 대상이 아닌 다른 승인 성공 이력이 있어야 하고 그 이력을 바꾸지 않는다
     * (D-AUTH-011). 검사 실패 시 아무것도 바뀌지 않는다. main 코드에서는 [IncidentHoldSettlement]만 부른다(ArchUnit — 한도 정리와 함께).
     */
    fun releaseIncidentHold(release: IncidentHoldRelease) {
        if (release.hold !in holds) throw IncidentHoldReleaseRejectedException(release.hold, "no such hold")
        if (releases.any { it.hold == release.hold }) throw IncidentHoldReleaseRejectedException(release.hold, "already released")
        if (release.hold.reason == IncidentReason.CANCEL_RESULT_CONFLICT) {
            // review m-1: 취소 사건의 해제 판정·FORCED 반환 출구는 RECON-CONTRACT-001이 정한다. 그 전까지 해제하지 않는다(fail-closed).
            throw IncidentHoldReleaseRejectedException(release.hold, "cancel result conflict release is defined by RECON-CONTRACT-001")
        }
        val existing = authorizedPaymentId
        when (release.finding) {
            HoldReleaseFinding.REAL_APPROVAL_CONFIRMED -> {
                if (existing != null && existing != release.hold.paymentId) {
                    throw IncidentHoldReleaseRejectedException(
                        release.hold, "order already has another authorized payment (use DOUBLE_APPROVAL_CORRECTED with a correction reference)",
                    )
                }
                authorizedPaymentId = release.hold.paymentId
            }
            HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED ->
                if (existing == null || existing == release.hold.paymentId) {
                    throw IncidentHoldReleaseRejectedException(release.hold, "a double approval needs another authorized payment on the order")
                }
            HoldReleaseFinding.NO_EFFECT_CONFIRMED -> {}
        }
        releases += release
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
            "authorized=${authorizedPaymentId?.value}, holds=${holds.size}, released=${releases.size})"

    companion object {
        /** control이 아직 없는 주문의 판정: 첫 Payment를 generation 1로 만든다. */
        fun decideForNewOrder(): PaymentSlotDecision.CreateNewPayment = PaymentSlotDecision.CreateNewPayment(1)

        /** 첫 Payment(REQUESTED, generation 1)로 주문 슬롯을 연다. 고정 입력은 첫 Payment의 fingerprint로 정해진다. */
        fun open(firstPayment: Payment): OrderPaymentControl {
            require(firstPayment.status == REQUESTED) { "first payment must be REQUESTED: ${firstPayment.status}" }
            require(firstPayment.slotGeneration == 1L) { "first payment must be generation 1: ${firstPayment.slotGeneration}" }
            return OrderPaymentControl(firstPayment.orderKey, firstPayment.intent, firstPayment.id, 1)
        }

        /**
         * 영속 복원(persistence Mapper 전용, CP-DB-001). 슬롯을 새로 열지 않고 저장된 control을 되돌린다. 시험 편의 생성 API가 아니다
         * (Named Test Fixture는 [open]·[occupy]를 쓴다). generation ≥ 1, 사건 보류는 (paymentId, reason)당 하나여야 한다 — 위반 행은
         * 조용히 고치지 않고 거부한다. 동시 수정 감지(JPA version)는 domain 값이 아니라 adapter가 따로 관리한다.
         * [incidentHoldReleases]는 저장된 보류의 해제 기록(보류당 최대 하나)이다. 영속 표는 AUTH-001 이후라 기본값은 빈 목록이다 — 해제
         * 기록이 저장되지 않은 동안 복원된 보류는 활성으로 남는다(fail-closed).
         */
        fun reconstitute(
            orderKey: OrderKey,
            fixedIntent: OrderIntentFingerprint,
            currentPaymentId: PaymentId,
            generation: Long,
            authorizedPaymentId: PaymentId?,
            incidentHolds: List<IncidentHold>,
            incidentHoldReleases: List<IncidentHoldRelease> = emptyList(),
        ): OrderPaymentControl {
            require(generation >= 1) { "generation must be positive: $generation" }
            require(incidentHolds.toSet().size == incidentHolds.size) { "incident holds must be unique per (paymentId, reason, occurrence)" }
            require(incidentHolds.groupBy { it.paymentId to it.reason }.values.all { g -> g.map { it.occurrence }.sorted() == (1..g.size).toList() }) {
                "hold occurrences per (paymentId, reason) must be 1..n"
            }
            require(incidentHoldReleases.all { it.hold in incidentHolds }) { "a hold release must refer to a stored hold" }
            require(incidentHoldReleases.map { it.hold }.toSet().size == incidentHoldReleases.size) { "a hold is released at most once" }
            // review n-6: 해제 판정과 주문 승인 이력의 일관성(releaseIncidentHold가 만드는 상태만 복원한다).
            incidentHoldReleases.forEach { r ->
                when (r.finding) {
                    HoldReleaseFinding.REAL_APPROVAL_CONFIRMED -> require(authorizedPaymentId == r.hold.paymentId) {
                        "a REAL_APPROVAL_CONFIRMED release requires the released payment to be the order's authorized payment"
                    }
                    HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED -> require(authorizedPaymentId != null && authorizedPaymentId != r.hold.paymentId) {
                        "a DOUBLE_APPROVAL_CORRECTED release requires another authorized payment on the order"
                    }
                    HoldReleaseFinding.NO_EFFECT_CONFIRMED -> {}
                }
            }
            return OrderPaymentControl(orderKey, fixedIntent, currentPaymentId, generation).also {
                it.authorizedPaymentId = authorizedPaymentId
                it.holds += incidentHolds
                it.releases += incidentHoldReleases
            }
        }
    }
}
