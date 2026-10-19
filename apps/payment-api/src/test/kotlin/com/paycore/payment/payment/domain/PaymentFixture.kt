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
        Payment.request(paymentId(n), demoOrder, intent, slotGeneration, demoWindow)

    /** Demo 결제 유효기간 설정 버전(D-AUTHN-005 — 운영값 아님). */
    const val CHECKOUT_VALIDITY_VERSION = "checkout-validity-demo-1"

    /**
     * 기본 결제창 시각(Demo): 승인 시험 시각(AuthorizationFixture.startedAt 2026-10-15T03:00Z)보다 30분 뒤라 기존 domain 시험의 판정 시각에서 경과하지 않는다.
     * 결제창 만료를 보는 시험은 [requestedWithCheckoutWindow]로 시각을 명시한다.
     */
    val demoWindow = CheckoutWindow(java.time.Instant.parse("2026-10-15T03:30:00Z"), CHECKOUT_VALIDITY_VERSION)

    /** V7 이전에 저장된 REQUESTED 행(결제창 시각 없음, D-AUTHN-007 채우기 대상) — 영속 복원 경로로만 존재한다. */
    fun storedWithoutWindow(status: PaymentStatus = REQUESTED, n: Int = 1): Payment =
        Payment.reconstitute(paymentId(n), demoOrder, originalIntent, 1, status, emptyList(), null, authorizationStarted = status in setOf(AUTHORIZING, UNKNOWN, AUTHORIZED))

    /** 결제창 시각이 [expiresAt]으로 고정된 REQUESTED(AUTHN-001 §7.5 — 생성 시각 + Demo 유효기간을 application이 계산해 넘긴 값). */
    fun requestedWithCheckoutWindow(expiresAt: java.time.Instant, n: Int = 1): Payment =
        Payment.request(paymentId(n), demoOrder, originalIntent, 1, CheckoutWindow(expiresAt, CHECKOUT_VALIDITY_VERSION))

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

    // --- 종료됐지만 새 시도 근거가 불충분/없음 ---
    // AUTH-001 L-3: REQUESTED(미전송)에서는 근거 없음과 EXPIRED + NOT_TRANSMITTED_AND_SESSION_DISCARDED만 정상 경로다. timeout·조회 NOT_FOUND·lease 만료·
    // 교차 조합처럼 미전송 Payment에 있을 수 없는 근거로 종료된 상태는 **AUTH-001 이전에 저장된 legacy 행**으로만 존재한다 — [legacyStored](영속 복원 경로).
    // 이 상태들의 판정 기대값(새 Payment 차단)은 바뀌지 않는다(AUTH-001 AC 8 — Reviewer 확인 대상).

    fun expiredWithoutEvidence(n: Int = 1, slotGeneration: Long = 1): Payment = requested(n, slotGeneration).advance(EXPIRED)

    /** legacy 행: AUTH-001 이전에 REQUESTED에서 TIMEOUT으로 종료되어 저장된 Payment. */
    fun failedWithOnlyTimeout(n: Int = 1, slotGeneration: Long = 1): Payment = legacyStored(FAILED, TIMEOUT, n, slotGeneration)

    /** legacy 행: AUTH-001 이전에 REQUESTED에서 조회 NOT_FOUND로 종료되어 저장된 Payment. */
    fun expiredWithOnlyInquiryNotFound(n: Int = 1, slotGeneration: Long = 1): Payment = legacyStored(EXPIRED, INQUIRY_NOT_FOUND, n, slotGeneration)

    /**
     * AUTH-001 L-3 이전에 저장돼 지금은 정상 전이로 만들 수 없는 종료 행(미전송 REQUESTED + 기관·전송 근거). 영속 복원(`Payment.reconstitute`)은 전이 경로를 검사하지 않으므로
     * 이런 행은 계속 복원된다. 시험 이름에 legacy를 넣어 정상 경로와 구별한다. AUTH-001 이전에는 전송 경로가 없었으므로 승인을 시작하지 않은 행이다(검토 #1 m-1).
     */
    fun legacyStored(status: PaymentStatus, evidence: TerminationEvidence?, n: Int = 1, slotGeneration: Long = 1): Payment {
        require(status == FAILED || status == EXPIRED) { "legacy rows are terminated rows: $status" }
        return Payment.reconstitute(
            paymentId(n), demoOrder, originalIntent, slotGeneration, status, listOfNotNull(evidence).map { TerminationEvidenceRecord(it, null) }, demoWindow,
            authorizationStarted = false,
        )
    }

    /**
     * [advance]가 공개 전이로 만들 수 있는 종료(상태, 근거) 조합인가(검토 #1 n-1). 승인 시작 이후 경로는 목표 상태에 충분한 기관·전송 전 실패 근거, 미전송 REQUESTED 경로는 근거 없음과
     * EXPIRED + 미전송·세션 폐기뿐이다(AUTH-001 L-3). 그 밖의 조합은 시험이 [legacyStored]를 명시해서 준비한다.
     */
    fun reachableTermination(status: PaymentStatus, evidence: TerminationEvidence?): Boolean {
        require(status == FAILED || status == EXPIRED) { "terminations only: $status" }
        return afterAuthorizationStart(status, evidence) || untransmittedPath(status, evidence)
    }

    private fun afterAuthorizationStart(status: PaymentStatus, evidence: TerminationEvidence?) =
        evidence != null && evidence != NOT_TRANSMITTED_AND_SESSION_DISCARDED && TerminationEvidenceOracle.isSufficient(status, evidence)

    private fun untransmittedPath(status: PaymentStatus, evidence: TerminationEvidence?) =
        evidence == null || (status == EXPIRED && evidence == NOT_TRANSMITTED_AND_SESSION_DISCARDED)


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
                // 승인 시작 이후 경로는 기관 결과·전송 전 실패 근거가 목표 상태에 충분할 때만(oracle 기준). 미전송 REQUESTED 경로는 근거 없음과
                // EXPIRED + 미전송·세션 폐기뿐(AUTH-001 L-3). 그 밖(불충분 근거·교차 조합)은 정상 전이로 만들 수 없다 — 조용히 legacy 행으로 바꾸지 않고 거부한다
                // (검토 #1 n-1: 호출자가 legacyStored를 명시한다).
                when {
                    afterAuthorizationStart(status, evidence) -> { startAuthorization(); if (status == FAILED) fail(evidence) else expire(evidence) }
                    untransmittedPath(status, evidence) -> if (status == FAILED) fail(evidence) else expire(evidence)
                    else -> throw IllegalArgumentException("$status with $evidence is not reachable by transitions: use PaymentFixture.legacyStored explicitly")
                }
            }
        }
        return this
    }
}
