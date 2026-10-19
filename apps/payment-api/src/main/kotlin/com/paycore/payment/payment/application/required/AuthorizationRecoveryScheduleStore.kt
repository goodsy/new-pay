package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import java.time.Instant

/**
 * Attempt 하나의 회복 일정(AUTH-001 AU-16 — domain 밖 bookkeeping, Store ③). TX1이 Attempt와 함께 만들고([nextCheckAt] = PREPARED 마감), TXd가 dispatch lease를 적고,
 * TX2·회복이 결론(closed)·다음 조회 시각·조회 횟수·이관 시각을 갱신한다. 금융 판단의 근거가 아니다 — 판단은 잠근 Aggregate가 한다.
 */
data class RecoverySchedule(
    val attemptId: AttemptId,
    val authorizationId: AuthorizationId,
    val paymentId: PaymentId,
    val orderKey: OrderKey,
    val dispatchLeaseUntil: Instant?,
    val nextCheckAt: Instant,
    val inquiryCount: Int,
    val escalatedAt: Instant?,
    val closedAt: Instant?,
    /** TX1이 적은 회복 설정 버전(검토 #3 m-4 — Auditability). */
    val recoveryConfigVersion: String,
    /** 이관 판단 때의 회복 설정 버전(이관 전 null). */
    val escalationConfigVersion: String? = null,
) {
    init {
        require(inquiryCount >= 0) { "inquiry count must not be negative" }
        require((escalatedAt == null) == (escalationConfigVersion == null)) { "the escalation config version is recorded exactly with the escalation" }
    }
}

/** 회복 일정 저장 Port(TX 필수 — MANDATORY). 잠금 순서는 업무 행 다음(§4.7 끝). 후보 선점은 단독 짧은 TX의 `SKIP LOCKED`. */
interface AuthorizationRecoveryScheduleStore {
    fun insert(schedule: RecoverySchedule, at: Instant)

    fun lock(attemptId: AttemptId): RecoverySchedule?

    fun update(schedule: RecoverySchedule, at: Instant)

    /** 아직 닫히지 않았고 [now]에 점검할 일정의 Attempt를 최대 [limit]개 잠가 가져온다(다른 Worker가 잡은 행은 건너뜀 — SKIP LOCKED). */
    fun claimDue(now: Instant, limit: Int): List<RecoverySchedule>
}
