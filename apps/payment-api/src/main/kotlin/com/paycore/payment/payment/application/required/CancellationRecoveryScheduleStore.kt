package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import java.time.Instant

/**
 * 취소 Attempt 하나의 회복 일정(CANCEL-001 CN-24 — AUTH-001 `RecoverySchedule` 설계 복제, domain 밖 bookkeeping, Store ③). TX1이 Attempt와 함께 만들고([nextCheckAt] = PREPARED 마감),
 * TXd가 dispatch lease를 적고, TX2·회복이 결론·다음 조회 시각·조회 횟수·이관을 갱신한다. 금융 판단의 근거가 아니다 — 판단은 잠근 Aggregate가 한다.
 */
data class CancellationRecoverySchedule(
    val attemptId: CancelAttemptId,
    val cancellationId: CancellationId,
    val paymentId: PaymentId,
    val orderKey: OrderKey,
    val dispatchLeaseUntil: Instant?,
    val nextCheckAt: Instant,
    val inquiryCount: Int,
    val escalatedAt: Instant?,
    val closedAt: Instant?,
    /** TX1이 적은 취소 회복 설정 버전(Auditability). */
    val recoveryConfigVersion: String,
    /** 이관 판단 때의 회복 설정 버전(이관 전 null). */
    val escalationConfigVersion: String? = null,
) {
    init {
        require(inquiryCount >= 0) { "inquiry count must not be negative" }
        require((escalatedAt == null) == (escalationConfigVersion == null)) { "the escalation config version is recorded exactly with the escalation" }
    }
}

/** 취소 회복 일정 저장 Port(TX 필수 — MANDATORY). 잠금 순서는 업무 행 다음(명세 §4.7 끝). 후보 선점은 단독 짧은 TX의 `SKIP LOCKED`. */
interface CancellationRecoveryScheduleStore {
    fun insert(schedule: CancellationRecoverySchedule, at: Instant)

    fun lock(attemptId: CancelAttemptId): CancellationRecoverySchedule?

    fun update(schedule: CancellationRecoverySchedule, at: Instant)

    /** 아직 닫히지 않았고 [now]에 점검할 일정을 최대 [limit]개 잠가 가져온다(다른 Worker가 잡은 행은 건너뜀 — SKIP LOCKED). */
    fun claimDue(now: Instant, limit: Int): List<CancellationRecoverySchedule>
}
