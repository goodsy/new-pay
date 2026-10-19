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
 * - REQUESTED는 아직 승인을 시작하지 않아 미전송이므로 결제창 만료·종료가 가능하다. 근거는 없음 또는 EXPIRED + NOT_TRANSMITTED_AND_SESSION_DISCARDED만 받는다
 *   (AUTH-001 L-3 — 미전송인데 기관 확정 거절·timeout·조회 NOT_FOUND·전송 전 실패 같은 근거는 출처가 있을 수 없다). "REQUESTED = 미전송"은 Attempt 생성과
 *   AUTHORIZING 전이가 같은 TX1에서 커밋되어(AuthorizationStart) 성립한다. AUTH-001 이전 저장 행은 [reconstitute]가 경로 검사 없이 복원한다.
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

    private val evidence = mutableListOf<TerminationEvidenceRecord>()

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
        get() = evidence.map { it.evidence }

    /** 기록된 종료 근거와 출처(AUTH-001 rev 0.3 m-6, append-only 사본). 출처가 null인 행은 AUTH-001 이전 저장 행뿐이다. */
    val terminationEvidenceRecords: List<TerminationEvidenceRecord>
        get() = evidence.toList()

    /**
     * 승인을 시작한 적이 있는가(AUTH-001 검토 #1 m-1). [startAuthorization]이 true로 바꾸고 되돌리지 않는다. 저장 열은 없고 영속 복원이 도출해 넘긴다([reconstitute]):
     * 승인 시작 상태(AUTHORIZING·UNKNOWN·AUTHORIZED)·이 Payment의 승인 행(TX1이 AUTHORIZING 전이와 같은 TX에 INSERT)·승인 시작 뒤에만 생기는 출처(관찰·포기한 Attempt)의
     * 종료 근거 중 하나라도 있으면 시작한 것이다. 출처 없는 legacy 종료 행(AUTH-001 이전 — 그때는 전송 경로가 없었다)은 시작하지 않은 것이다.
     */
    var authorizationStarted: Boolean = false
        private set

    /** 승인을 시작한다(외부 승인 Attempt 점유는 AUTH). 오래된/만료된 결제창은 시작할 수 없다. */
    fun startAuthorization() {
        transition("startAuthorization", from = setOf(REQUESTED), to = AUTHORIZING)
        authorizationStarted = true
    }

    /** 승인 결과가 불명확하다(timeout·응답 유실 등). */
    fun markUnknown() {
        transition("markUnknown", from = setOf(AUTHORIZING), to = UNKNOWN)
    }

    /** 승인 성공. */
    fun markAuthorized() {
        transition("markAuthorized", from = setOf(AUTHORIZING, UNKNOWN), to = AUTHORIZED)
    }

    /** 실패로 종료한다. AUTHORIZING/UNKNOWN에서는 FAILED에 충분한 근거가 있어야 한다. 근거가 있으면 출처 기록([source])도 있어야 한다(m-6). */
    fun fail(evidence: TerminationEvidence?, source: TerminationEvidenceSource?) {
        close("fail", FAILED, evidence, source)
    }

    /** 만료로 종료한다. AUTHORIZING/UNKNOWN에서는 EXPIRED에 충분한 근거가 있어야 한다. 근거가 있으면 출처 기록([source])도 있어야 한다(m-6). */
    fun expire(evidence: TerminationEvidence?, source: TerminationEvidenceSource?) {
        close("expire", EXPIRED, evidence, source)
    }

    /**
     * 종료된 Payment에 나중에 확인된 근거를 출처와 함께 덧붙인다(append-only). 검토 #1 m-1: 승인을 시작하지 않은 Payment는 결제창 만료 근거
     * (NOT_TRANSMITTED_AND_SESSION_DISCARDED)만 받는다 — REQUESTED에서 근거 없이 종료한 뒤 기관·전송 근거를 덧붙여 "안전 종료"로 만드는 우회를 막는다(L-3과 같은 규칙을
     * 덧붙이기에도 적용).
     */
    fun addTerminationEvidence(evidence: TerminationEvidence, source: TerminationEvidenceSource?) {
        if (status != FAILED && status != EXPIRED) throw InvalidPaymentTransitionException(id, status, "addTerminationEvidence")
        requireSource(evidence, source)
        if (!authorizationStarted && evidence != TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED) {
            throw InvalidPaymentTransitionException(id, status, "addTerminationEvidence($evidence)")
        }
        this.evidence += TerminationEvidenceRecord(evidence, source)
    }

    /** FAILED/EXPIRED이고 그 상태에 충분한 D-CPD-002 근거가 하나라도 기록돼 있으면 새 Payment의 안전 종료 근거가 있다. */
    fun hasSafeTermination(): Boolean =
        (status == FAILED || status == EXPIRED) && evidence.any { it.evidence.isSufficientFor(status) }

    private fun close(action: String, target: PaymentStatus, evidence: TerminationEvidence?, source: TerminationEvidenceSource?) {
        when (status) {
            // AUTH-001 L-3: REQUESTED = 미전송(Attempt와 AUTHORIZING이 같은 TX1). 미전송 Payment에 기관 결과·전송 근거는 있을 수 없다 — 근거 없음과
            // 결제창 만료(EXPIRED + NOT_TRANSMITTED_AND_SESSION_DISCARDED)만 받는다. PRE_TRANSMISSION은 Attempt가 있는 AUTHORIZING에서만.
            REQUESTED ->
                if (evidence != null && !(target == EXPIRED && evidence == TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)) {
                    throw InvalidPaymentTransitionException(id, status, "$action($evidence)")
                }
            AUTHORIZING, UNKNOWN ->
                if (evidence == null || !evidence.isSufficientFor(target)) {
                    throw InvalidPaymentTransitionException(id, status, "$action(${evidence ?: "NO_EVIDENCE"})")
                }
            AUTHORIZED, FAILED, EXPIRED -> throw InvalidPaymentTransitionException(id, status, action)
        }
        if (evidence == null) {
            require(source == null) { "a source belongs to recorded evidence only" }
        } else {
            requireSource(evidence, source)
        }
        status = target
        if (evidence != null) this.evidence += TerminationEvidenceRecord(evidence, source)
    }

    /**
     * 새 근거의 출처 기록 검사(AUTH-001 rev 0.3 m-6, AUTH-CONTRACT-001 §5.5 유일 출처 표): 확정 거절·무효과 확정 ← 관찰, 전송 전 실패 확정 ← 포기한 Attempt,
     * 미전송·세션 폐기 ← 결제창 만료. 불충분 근거(timeout 등)는 종류를 제한하지 않는다(새 Payment 근거가 아니다).
     */
    private fun requireSource(evidence: TerminationEvidence, source: TerminationEvidenceSource?) {
        requireNotNull(source) { "new termination evidence needs a source record" }
        val expected = when (evidence) {
            TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE, TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT -> TerminationEvidenceSourceKind.OBSERVATION
            TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED -> TerminationEvidenceSourceKind.ATTEMPT_ABANDON
            TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED -> TerminationEvidenceSourceKind.CHECKOUT_EXPIRY
            TerminationEvidence.TIMEOUT, TerminationEvidence.INQUIRY_NOT_FOUND, TerminationEvidence.LEASE_EXPIRED -> null
        }
        require(expected == null || source.kind == expected) { "evidence $evidence must come from a $expected source" }
    }

    private fun transition(action: String, from: Set<PaymentStatus>, to: PaymentStatus) {
        if (status !in from) throw InvalidPaymentTransitionException(id, status, action)
        status = to
    }

    override fun toString(): String = "Payment(id=${id.value}, status=$status, slotGeneration=$slotGeneration)"

    companion object {
        /** 승인 시작 뒤에만 생기는 종료 근거 출처(검토 #1 m-1 — 영속 복원의 [authorizationStarted] 도출·검사). */
        val STARTED_SOURCES: Set<TerminationEvidenceSourceKind> = setOf(TerminationEvidenceSourceKind.OBSERVATION, TerminationEvidenceSourceKind.ATTEMPT_ABANDON)

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
         * 저장 상태가 domain에서 존재할 수 없으면 거부한다(조용히 고치지 않는다): slotGeneration ≥ 1, 종료 근거는 FAILED/EXPIRED에만,
         * [authorizationStarted](검토 #1 m-1 — 영속 어댑터가 도출)는 REQUESTED면 false·AUTHORIZING/UNKNOWN/AUTHORIZED면 true·관찰·포기한 Attempt 출처 근거가 있으면 true.
         * 어떤 전이 경로로 종료됐는지(REQUESTED 종료는 근거 불요)는 행에 없어 검사하지 않는다 — 그 검사는 전이 시점(종료 메서드)의 책임이다.
         */
        fun reconstitute(
            id: PaymentId,
            orderKey: OrderKey,
            intent: OrderIntentFingerprint,
            slotGeneration: Long,
            status: PaymentStatus,
            terminationEvidence: List<TerminationEvidenceRecord>,
            checkoutWindow: CheckoutWindow?,
            authorizationStarted: Boolean,
        ): Payment {
            require(slotGeneration >= 1) { "slotGeneration must be positive: $slotGeneration" }
            require(terminationEvidence.isEmpty() || status == FAILED || status == EXPIRED) {
                "termination evidence is only stored for FAILED/EXPIRED payments: $status"
            }
            require(status != REQUESTED || !authorizationStarted) { "a REQUESTED payment has not started authorization" }
            require(status !in setOf(AUTHORIZING, UNKNOWN, AUTHORIZED) || authorizationStarted) { "a $status payment has started authorization" }
            require(authorizationStarted || terminationEvidence.none { it.source?.kind in STARTED_SOURCES }) {
                "evidence from an observation or an abandoned attempt exists only after authorization started"
            }
            return Payment(id, orderKey, intent, slotGeneration).also {
                it.authorizationStarted = authorizationStarted
                it.status = status
                it.evidence += terminationEvidence
                it.checkoutWindow = checkoutWindow
            }
        }
    }
}
