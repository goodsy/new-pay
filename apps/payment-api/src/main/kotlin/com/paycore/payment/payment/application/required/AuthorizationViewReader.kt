package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.AuthorizationStatus
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.ProviderRequestRef
import com.paycore.payment.payment.domain.TerminationEvidence
import java.time.Instant

/** 승인 조회 한 줄(CQRS 조회 측 — 비권위, 상태 변경은 JPA 잠금 재판정). */
data class AuthorizationViewRow(
    val authorizationId: AuthorizationId,
    val paymentId: PaymentId,
    val orderKey: OrderKey,
    val status: AuthorizationStatus,
    val failureEvidence: TerminationEvidence?,
    val approved: ApprovedTerms?,
    val approvedAt: Instant?,
)

/**
 * Attempt 조회 한 줄(회복 Worker의 비권위 후보 정보 — 판정은 잠금 재판정). [routeRef]는 Authorization Admission에 고정된 Route.
 * [routeProfileVersion]은 전송 게이트 통과 때 고정한 Route 프로파일 버전(D-AUTH1-007 — PREPARED·V14 이전 행은 null). 한 번 정해지면 바뀌지 않는다.
 */
data class AuthorizationAttemptViewRow(
    val attemptId: AttemptId,
    val authorizationId: AuthorizationId,
    val state: AttemptState,
    val preparedAt: Instant,
    val providerRequest: ProviderRequestRef,
    val routeRef: String,
    val routeProfileVersion: String?,
)

/** 승인 조회 Port(jOOQ — `adapter.query`, 읽기 전용 TX). confirm 응답·재생과 결과 반영 전 주문 키 찾기(잠금 순서상 control을 먼저 잡기 위해)에 쓴다. */
interface AuthorizationViewReader {
    fun find(authorizationId: AuthorizationId): AuthorizationViewRow?

    fun findAttempt(attemptId: AttemptId): AuthorizationAttemptViewRow?
}
