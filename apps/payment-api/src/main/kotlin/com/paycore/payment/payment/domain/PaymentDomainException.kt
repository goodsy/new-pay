package com.paycore.payment.payment.domain

/** payment 모듈 domain 규칙 위반. 오류 코드·HTTP 매핑은 CP-CONTRACT-001/CP-API에서 정한다. */
sealed class PaymentDomainException(message: String) : RuntimeException(message)

/** 현재 상태에서 허용되지 않는 Payment 상태 전이. */
class InvalidPaymentTransitionException(
    val paymentId: PaymentId,
    val status: PaymentStatus,
    val action: String,
) : PaymentDomainException("payment ${paymentId.value} in $status does not allow $action")

/**
 * 주문 슬롯의 currentPaymentId·generation과 맞지 않는 Payment/판정으로 슬롯을 바꾸거나 판정하려 했다(오래된 결과·오래된 판정).
 * 새 점유는 유지된다. 호출자는 control과 현재 Payment를 다시 읽어 판정해야 한다.
 */
class StaleSlotException(
    val orderKey: OrderKey,
    val currentPaymentId: PaymentId,
    val generation: Long,
    val offeredPaymentId: PaymentId,
    val offeredGeneration: Long,
) : PaymentDomainException(
    "stale slot: current ${currentPaymentId.value}@$generation, offered ${offeredPaymentId.value}@$offeredGeneration",
)

/** 판정이 새 Payment 생성이 아닌데 새 Payment로 슬롯을 점유하려 했다. */
class SlotOccupationRejectedException(
    val decision: PaymentSlotDecision,
) : PaymentDomainException("new payment occupation rejected: $decision")

/**
 * 새 Payment가 이 주문이 이미 아는 Payment ID(현재 점유·과거 승인 성공·사건 보류 대상)를 다시 쓰려 했다. Payment ID는 재사용하지 않는다.
 * 주문을 넘는 전역 유일성은 영속 PK(CP-DB-001)가 보장한다.
 */
class PaymentIdReuseException(
    val orderKey: OrderKey,
    val paymentId: PaymentId,
) : PaymentDomainException("payment id ${paymentId.value} is already used by this order")

/** 현재 상태에서 허용되지 않는 인증 세션 전이(AUTH-CONTRACT-001 §5.3). */
class InvalidAuthenticationTransitionException(
    val sessionId: AuthenticationSessionId,
    val status: AuthenticationStatus,
    val action: String,
) : PaymentDomainException("authentication session ${sessionId.value} in $status does not allow $action")

/** 이미 VERIFIED인 세션에 다른 인증 증거가 왔다. 덮어쓰지 않는다(보안 사건으로 기록할 대상). */
class AuthenticationEvidenceConflictException(
    val sessionId: AuthenticationSessionId,
) : PaymentDomainException("authentication session ${sessionId.value} already verified with different evidence")

/** 현재 상태에서 허용되지 않는 승인·Attempt 전이(AUTH-CONTRACT-001 §5.2). */
class InvalidAuthorizationTransitionException(
    val authorizationId: AuthorizationId,
    val state: String,
    val action: String,
) : PaymentDomainException("authorization ${authorizationId.value} in $state does not allow $action")

/** 한도 점유가 정책을 넘는다(사전 판정 없이 점유하려 한 호출 결함 — 업무 거절은 [AuthorizationStartResult.Rejected]). */
class LimitExceededException(
    val reason: LimitRejection,
) : PaymentDomainException("transaction limit exceeded: $reason")

/** 사건 보류 해제를 받아들일 수 없다(없는 보류·이미 해제·다른 승인 성공 이력과 충돌). */
class IncidentHoldReleaseRejectedException(
    val hold: IncidentHold,
    val why: String,
) : PaymentDomainException("incident hold release for ${hold.paymentId.value}/${hold.reason} rejected: $why")

/** 주문 차단(incident hold·과거 승인 성공) 때문에 승인을 시작할 수 없다. */
class AuthorizationStartRejectedException(
    val paymentId: PaymentId,
    val reason: BlockReason,
) : PaymentDomainException("authorization start for ${paymentId.value} rejected: $reason")
