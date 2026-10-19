package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.IdempotencyRecordId
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.PaymentTransactionId
import java.time.Instant

/** 승인 시작 한 번의 서버 생성 식별자(AUTH-001 AU-30): 승인 `pau_`·Attempt `pat_`·기관 요청 `ar_`(CA-22) + 시각 [Instant]의 ULID. */
data class AuthorizationIdentifiers(val authorizationId: AuthorizationId, val attemptId: AttemptId, val requestId: String)

/** 승인 경로 서버 생성 식별자(난수 — 추측 불가, 같은 값 재사용 없음). */
interface AuthorizationIdentifierGenerator {
    fun next(at: Instant): AuthorizationIdentifiers

    /** confirm 멱등 기록 ID(UUID v4). */
    fun idempotencyRecordId(): IdempotencyRecordId

    /** 조회 ID(`aq_` + ULID, AU-30) — 조회마다 새 값. */
    fun inquiryId(at: Instant): String

    /** 확정 금융 거래 ID(`ptx_` + ULID). */
    fun transactionId(at: Instant): PaymentTransactionId
}
