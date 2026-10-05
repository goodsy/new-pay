package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.OrderFixture.originalIntent
import com.paycore.payment.payment.domain.OrderFixture.paymentId
import com.paycore.payment.payment.domain.OrderFixture.demoOrder
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZED
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZING
import com.paycore.payment.payment.domain.PaymentStatus.EXPIRED
import com.paycore.payment.payment.domain.PaymentStatus.FAILED
import com.paycore.payment.payment.domain.PaymentStatus.REQUESTED
import com.paycore.payment.payment.domain.PaymentStatus.UNKNOWN
import com.paycore.payment.payment.domain.TerminationEvidence.INQUIRY_NOT_FOUND
import com.paycore.payment.payment.domain.TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE
import com.paycore.payment.payment.domain.TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT
import com.paycore.payment.payment.domain.TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED
import com.paycore.payment.payment.domain.TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED
import com.paycore.payment.payment.domain.TerminationEvidence.TIMEOUT

/**
 * Payment Named Test Fixture(Test Fixture Policy: Domain Factory → Named Test Fixture). 모든 상태는 domain 공식 생성
 * (`Payment.request`)과 공개 상태 전이로만 만든다. reflection·private field 주입·검증 우회 없음.
 *
 * 상태와 종료 근거는 독립적으로 고르지 않고 업무 시나리오 경로로 함께 만든다([advance]):
 * - 기관 결과가 근거인 종료(확정 거절·무효과 확인)와 승인 시작 후 전송 전 실패 확정은, 그 근거가 목표 상태에 충분할 때
 *   승인 시작(AUTHORIZING) 이후 경로
 * - 미전송+세션 폐기, 근거 없음/불충분 종료, 표에 없는 교차 조합(예: EXPIRED + 확정 거절, D-CPD-003 불인정)은 REQUESTED(아직 승인
 *   시작 전) 경로
 *   (AUTHORIZING/UNKNOWN에서는 불충분한 근거로 종료할 수 없다 — 확정 문서 §3)
 */
object PaymentFixture {

    /** 결제창이 열린, 아직 승인을 시작하지 않은 시도. */
    fun requested(n: Int = 1, slotGeneration: Long = 1, intent: OrderIntentFingerprint = originalIntent): Payment =
        Payment.request(paymentId(n), demoOrder, intent, slotGeneration)

    /** 승인 Attempt를 점유한(전송했을 수 있는) 시도. */
    fun authorizing(n: Int = 1, slotGeneration: Long = 1): Payment = requested(n, slotGeneration).advance(AUTHORIZING)

    /** 승인 결과가 불명확한 시도. */
    fun unknownAuthorization(n: Int = 1, slotGeneration: Long = 1): Payment = requested(n, slotGeneration).advance(UNKNOWN)

    /** 승인 성공한 시도. */
    fun authorized(n: Int = 1, slotGeneration: Long = 1): Payment = requested(n, slotGeneration).advance(AUTHORIZED)

    // --- 안전 종료(D-CPD-002 충분 근거) ---

    fun failedWithConfirmedDecline(n: Int = 1, slotGeneration: Long = 1): Payment =
        requested(n, slotGeneration).advance(FAILED, INSTITUTION_CONFIRMED_DECLINE)

    fun failedBeforeTransmission(n: Int = 1, slotGeneration: Long = 1): Payment =
        requested(n, slotGeneration).advance(FAILED, PRE_TRANSMISSION_FAILURE_CONFIRMED)

    fun failedWithConfirmedNoEffect(n: Int = 1, slotGeneration: Long = 1): Payment =
        requested(n, slotGeneration).advance(FAILED, INSTITUTION_CONFIRMED_NO_EFFECT)

    fun expiredUntransmittedWithSessionDiscarded(n: Int = 1, slotGeneration: Long = 1): Payment =
        requested(n, slotGeneration).advance(EXPIRED, NOT_TRANSMITTED_AND_SESSION_DISCARDED)

    fun expiredWithConfirmedNoEffect(n: Int = 1, slotGeneration: Long = 1): Payment =
        requested(n, slotGeneration).advance(EXPIRED, INSTITUTION_CONFIRMED_NO_EFFECT)

    // --- 종료됐지만 새 시도 근거가 불충분/없음(REQUESTED 경로) ---
    // 아래 Payment는 승인을 시작하지 않은 REQUESTED(미전송)에서 종료한다. 현재 domain은 REQUESTED 종료 근거의 출처(미전송인데
    // timeout·조회 NOT_FOUND 근거 등)를 검증하지 않고 주어진 대로 기록한다(Payment KDoc). AUTH-001에서 출처를 제한하면 이 fixture와
    // OrderPaymentDecisionTableSpec 행을 함께 갱신한다. 이미 전송한 AUTHORIZING/UNKNOWN은 이 근거로 종료할 수 없다(PaymentSpec).

    fun expiredWithoutEvidence(n: Int = 1, slotGeneration: Long = 1): Payment = requested(n, slotGeneration).advance(EXPIRED)

    fun failedWithOnlyTimeout(n: Int = 1, slotGeneration: Long = 1): Payment =
        requested(n, slotGeneration).advance(FAILED, TIMEOUT)

    fun expiredWithOnlyInquiryNotFound(n: Int = 1, slotGeneration: Long = 1): Payment =
        requested(n, slotGeneration).advance(EXPIRED, INQUIRY_NOT_FOUND)

    /** 표 기반 시험용: 근거가 필요 없는 상태(REQUESTED·AUTHORIZING·UNKNOWN·AUTHORIZED)의 정상 경로 Payment. */
    fun inStatus(status: PaymentStatus, n: Int = 1, slotGeneration: Long = 1): Payment {
        require(status != FAILED && status != EXPIRED) { "FAILED/EXPIRED need evidence: use requested().advance(status, evidence)" }
        return requested(n, slotGeneration).advance(status)
    }

    /**
     * REQUESTED Payment를 업무 경로대로 [status]까지 공개 전이로 진행한다. FAILED/EXPIRED의 경로는 [evidence]가 결정한다
     * (위 클래스 설명). OrderPaymentControlFixture가 control을 연 뒤 같은 경로를 쓰도록 공개한다.
     */
    fun Payment.advance(status: PaymentStatus, evidence: TerminationEvidence? = null): Payment {
        check(this.status == REQUESTED) { "fixture paths start from REQUESTED: ${this.status}" }
        require(evidence == null || status == FAILED || status == EXPIRED) { "evidence only for FAILED/EXPIRED: $status" }
        when (status) {
            REQUESTED -> {}
            AUTHORIZING -> startAuthorization()
            UNKNOWN -> { startAuthorization(); markUnknown() }
            AUTHORIZED -> { startAuthorization(); markAuthorized() }
            FAILED, EXPIRED -> {
                // 승인 시작 이후 경로는 기관 결과·전송 전 실패 근거가 목표 상태에 충분할 때만(oracle 기준). 그 밖(교차 조합 포함)은
                // 미전송 REQUESTED에서 주어진 근거로 종료한다 — domain이 허용하는 경로이며 새 시도 근거로는 불충분하다.
                val afterAuthorizationStart = evidence != null && evidence != NOT_TRANSMITTED_AND_SESSION_DISCARDED &&
                    TerminationEvidenceOracle.isSufficient(status, evidence)
                if (afterAuthorizationStart) startAuthorization()
                if (status == FAILED) fail(evidence) else expire(evidence)
            }
        }
        return this
    }
}
