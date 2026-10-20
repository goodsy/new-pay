package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.EventId
import com.paycore.payment.payment.domain.Environment
import java.time.Instant

/**
 * 통합 이벤트 Outbox 기록(CP-CONTRACT-001 §10 envelope). 업무 변경과 같은 PAYMENT 로컬 TX로 append한다. 전달(Relay)·발행 표시는
 * 이 Port 밖(별도 작업)이다. [effectKey]는 업무 효과당 하나(UNIQUE)라 같은 Payment의 생성 이벤트가 두 번 생기지 않는다.
 */
data class OutboxEvent(
    val eventId: EventId,
    val eventType: String,
    val schemaVersion: Int,
    val eventCategory: String,
    val producer: String,
    val aggregateType: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val occurredAt: Instant,
    val correlationId: String,
    val causationId: String,
    val effectKey: String,
    val payload: OutboxPayload,
)

/** 이벤트 payload(명시적 통합 계약 — Domain Entity 직렬화가 아니다). 직렬화 형식은 adapter 책임이다. */
sealed interface OutboxPayload

/**
 * PaymentRequested v1 payload(D-CPC-003). paymentKey(결제창 지목 키)·orderName·카드정보·Secret·개인정보는 넣지 않는다.
 * 금액은 통화 minor unit 정수, lifecycle은 항상 REQUESTED. 금융 사실이 아니다(eventCategory INTEGRATION_NON_FINANCIAL).
 */
data class PaymentRequestedV1Payload(
    val environment: Environment,
    val merchantAccountId: String,
    val merchantSiteId: String,
    val merchantOrderId: String,
    val paymentId: String,
    val amountMinor: Long,
    val currency: String,
    val paymentMethod: String,
    val slotGeneration: Long,
    val orderIntentFingerprint: String,
    val lifecycle: String,
    val requestedAt: Instant,
) : OutboxPayload

/**
 * PaymentExpired v1 payload(D-AUTHN-008, AUTHN-001 §7.5.7). PaymentRequested와 같은 기준: paymentKey·orderName·카드정보·Secret·개인정보, 결제창 토큰·세션·인증 세션 식별값은
 * 넣지 않는다. lifecycle은 항상 EXPIRED, 종료 근거는 NOT_TRANSMITTED_AND_SESSION_DISCARDED. 금융 사실이 아니다(INTEGRATION_NON_FINANCIAL).
 */
data class PaymentExpiredV1Payload(
    val environment: Environment,
    val merchantAccountId: String,
    val merchantSiteId: String,
    val merchantOrderId: String,
    val paymentId: String,
    val amountMinor: Long,
    val currency: String,
    val paymentMethod: String,
    val slotGeneration: Long,
    val lifecycle: String,
    val terminationEvidence: String,
    val checkoutExpiresAt: Instant,
    val checkoutValidityVersion: String,
    val expiredAt: Instant,
) : OutboxPayload

/**
 * PaymentAuthorized v1(AUTH-001 §4.10, AC1 §11 — FINANCIAL_FACT, effect_key `PaymentAuthorized:{transactionId}` = FINANCIAL 업무 키). 카드번호·Secret·인증 증거 원문·기관 계약계정
 * (D-MS2-005) 없음. 금액은 minor 정수. 기관 영업일은 기관이 준 값 그대로(의미는 Q-CALENDAR).
 */
data class PaymentAuthorizedV1Payload(
    val environment: Environment,
    val merchantAccountId: String,
    val merchantSiteId: String,
    val merchantOrderId: String,
    val paymentId: String,
    val authorizationId: String,
    val transactionId: String,
    val amountMinor: Long,
    val currency: String,
    val planKind: String,
    val installmentMonths: Int?,
    val cardType: String,
    val issuerCode: String,
    val acquirerCode: String,
    val approvalNumber: String,
    val providerTransactionId: String,
    val institutionTransactionDate: java.time.LocalDate,
    val institutionBusinessDate: java.time.LocalDate?,
    val approvedAt: Instant,
    val policyAssignmentVersion: String,
    val entitlementVersion: String,
) : OutboxPayload

/** PaymentAuthorizationFailed v1(AUTH-001 §4.10 — INTEGRATION_NON_FINANCIAL, 금융 효과 없음). [failureCategory]는 응답과 같은 고정 분류. */
data class PaymentAuthorizationFailedV1Payload(
    val environment: Environment,
    val merchantAccountId: String,
    val merchantOrderId: String,
    val paymentId: String,
    val authorizationId: String,
    val failureEvidence: String,
    val failureCategory: String,
    val failedAt: Instant,
) : OutboxPayload

/**
 * AuthorizationIncidentDetected v1(AUTH-001 §4.10 — RISK_INCIDENT, 새 hold가 append될 때만 — F-1·AU-17, effect_key에 occurrence — m-9). 여는 관찰의 금액·통화·승인번호를 담는다
 * (정산 대상 아님 — RECON Case 입력).
 */
data class AuthorizationIncidentDetectedV1Payload(
    val environment: Environment,
    val merchantAccountId: String,
    val merchantOrderId: String,
    val paymentId: String,
    val authorizationId: String,
    val reason: String,
    val occurrence: Int,
    val observationSource: String?,
    val observationResultId: String?,
    val observedOutcome: String?,
    val observedAmountMinor: Long?,
    val observedCurrency: String?,
    val observedApprovalNumber: String?,
    val detectedAt: Instant,
) : OutboxPayload

/** AuthorizationOutcomeUnresolved v1(AUTH-001 §6.2 — OPERATIONAL_RISK, 회복 예산 소진·연령 초과 → 대사 이관, 상태 불변). */
data class AuthorizationOutcomeUnresolvedV1Payload(
    val environment: Environment,
    val merchantAccountId: String,
    val merchantOrderId: String,
    val paymentId: String,
    val authorizationId: String,
    val attemptId: String,
    val requestId: String,
    val inquiryCount: Int,
    val unknownSince: Instant,
    val escalatedAt: Instant,
    /** 이관 판단에 쓴 회복 설정 버전(검토 #3 m-4). */
    val configVersion: String,
    val escalationReason: EscalationReason,
) : OutboxPayload

/**
 * PaymentCancelled v1(CANCEL-001 §4.10 — FINANCIAL_FACT, effect_key `PaymentCancelled:{transactionId}` CN-13). CC §9.3 목록 + 원 승인 Plan·cardType(§9.3 — 원 Authorization에서). 카드번호·Secret·
 * 운영자 개인정보·기관 계약계정 없음. 정책·권리 버전은 원 Authorization 고정값(INV-C6 — 현재 정책을 넣을 경로가 없다).
 */
data class PaymentCancelledV1Payload(
    val environment: Environment,
    val merchantAccountId: String,
    val merchantSiteId: String,
    val merchantOrderId: String,
    val paymentId: String,
    val authorizationId: String,
    val originalTransactionId: String,
    val cancellationId: String,
    val transactionId: String,
    val amountMinor: Long,
    val currency: String,
    val scope: String,
    val authorizedAmountMinor: Long,
    val returnSequence: Int,
    val cumulativeReturnedMinor: Long,
    val returnStatusAfter: String,
    val cancelApprovalNumber: String?,
    val providerTransactionId: String,
    val originalProviderTransactionId: String,
    val institutionTransactionDate: java.time.LocalDate,
    val institutionBusinessDate: java.time.LocalDate?,
    val cancelledAt: Instant,
    val policyAssignmentVersion: String,
    val entitlementVersion: String,
    val initiatorKind: String,
    val originalPlanKind: String,
    val originalInstallmentMonths: Int?,
    val originalCardType: String,
) : OutboxPayload

/** PaymentCancelFailed v1(CANCEL-001 §4.10 — INTEGRATION_NON_FINANCIAL, 금융 효과 없음, effect_key `PaymentCancelFailed:{cancellationId}`). */
data class PaymentCancelFailedV1Payload(
    val environment: Environment,
    val merchantAccountId: String,
    val merchantOrderId: String,
    val paymentId: String,
    val cancellationId: String,
    val amountMinor: Long,
    val currency: String,
    val failureEvidence: String,
    val failureCategory: String,
    val failedAt: Instant,
) : OutboxPayload

/**
 * CancellationIncidentDetected v1(CANCEL-001 §4.10 — RISK_INCIDENT, **사건마다** D-CANCEL1-002, effect_key `{cancellationId}:{incidentSeq}`). 위험 힌트 HIGH, 잔액 초과(FORCED excess)면
 * CRITICAL(D-CANCEL-006). 새 hold 여부와 사건을 연 관찰 키·조건을 담는다(RECON Case 입력 — 정산 대상 아님).
 */
data class CancellationIncidentDetectedV1Payload(
    val environment: Environment,
    val merchantAccountId: String,
    val merchantOrderId: String,
    val paymentId: String,
    val cancellationId: String,
    val incidentSeq: Int,
    val reason: String,
    val riskHint: String,
    /** 승인액을 넘은 반환 가능 금액(이 Payment 누적, 초과가 아니면 0 — 검토 #3 n-4). 통화는 원승인 통화. */
    val excessAmountMinor: Long,
    val holdOpened: Boolean,
    val holdOccurrence: Int?,
    val observationSource: String?,
    val observationResultId: String?,
    val observedOutcome: String?,
    val observedAmountMinor: Long?,
    val observedCurrency: String?,
    val observedProviderTransactionId: String?,
    val detectedAt: Instant,
) : OutboxPayload

/** CancellationOutcomeUnresolved v1(CANCEL-001 §4.10·§7 — OPERATIONAL_RISK, 회복 예산 소진·연령 초과 → 대사 이관, 취소 UNKNOWN·예약 RESERVED 유지 — D-CANCEL1-003 임시 출구 없음). */
data class CancellationOutcomeUnresolvedV1Payload(
    val environment: Environment,
    val merchantAccountId: String,
    val merchantOrderId: String,
    val paymentId: String,
    val cancellationId: String,
    val attemptId: String,
    val requestId: String,
    val inquiryCount: Int,
    val unknownSince: Instant,
    val escalatedAt: Instant,
    val configVersion: String,
    val escalationReason: EscalationReason,
) : OutboxPayload

/** 대사 이관 사유(검토 #3 m-4): 조회 예산 소진 또는 UNKNOWN 연령 상한(보존기간 도달 포함 — 기동 검사로 maxAge < 보존기간). */
enum class EscalationReason { BUDGET, MAX_AGE }

interface OutboxStore {
    /** event_id·effect_key 충돌은 [PersistenceFailure.UniqueConflict]. TX 없이 호출하면 거부한다. */
    fun append(event: OutboxEvent)
}
