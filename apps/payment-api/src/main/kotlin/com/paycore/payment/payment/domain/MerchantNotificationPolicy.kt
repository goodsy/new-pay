package com.paycore.payment.payment.domain

/** 가맹점 Webhook 이벤트 종류 v1(WEBHOOK-001 §4.1, D-WH-001·002). [wireName]은 공개 계약 값이다. */
enum class MerchantNotificationType(val wireName: String) {
    PAYMENT_AUTHORIZED("payment.authorized"),
    PAYMENT_FAILED("payment.failed"),
    PAYMENT_EXPIRED("payment.expired"),
    PAYMENT_CANCELLED("payment.cancelled"),
    PAYMENT_CANCEL_FAILED("payment.cancel_failed"),
}

/** 숨기는 이유(D-CPQ-002 (ii)·D-CANCEL-013 — 가맹점에 FAILED/EXPIRED로 알려진 P1). */
enum class SuppressionReason { LATE_APPROVAL_ON_TERMINATED_PAYMENT, CANCELLATION_ON_TERMINATED_PAYMENT }

/** Outbox 이벤트 하나의 가맹점 통지 판정. */
sealed interface MerchantNotificationDecision {
    data class Notify(val type: MerchantNotificationType) : MerchantNotificationDecision

    data class Suppress(val reason: SuppressionReason) : MerchantNotificationDecision

    /** 가맹점 통지 대상이 아닌 이벤트(생성·내부 위험/운영 이벤트·모르는 종류). */
    data object NotApplicable : MerchantNotificationDecision
}

/**
 * 통지 대상 이벤트가 그 이벤트가 생길 수 없는 Payment 상태와 함께 왔다(결함 또는 읽기 불가). 숨기지도 보내지도 않는다 — 호출자가 그 Outbox 행을 비종결 실패로 둔다
 * (WEBHOOK-001 rev 0.2 H-3, fail-closed). [paymentStatus]가 null이면 Payment 행을 읽지 못했다.
 */
class MerchantNotificationAnomalyException(val eventType: String, val paymentStatus: PaymentStatus?) :
    IllegalStateException("merchant notification for $eventType is impossible with payment status $paymentStatus")

/**
 * 가맹점 Webhook 분류 표(WEBHOOK-001 §4.1). 판정 입력은 Outbox 이벤트 종류와 **이벤트 커밋 뒤** 읽은 Payment 상태다. 판정에 쓰는 상태(AUTHORIZED·FAILED·EXPIRED)는
 * 모두 종료 상태라 이벤트 시점과 판정 시점이 같다(§4.1 도출). 개시자(가맹점·운영자)는 판정에 쓰지 않는다 — AUTHORIZED Payment의 운영자 보정 취소도 통지한다(D-WH-001).
 */
object MerchantNotificationPolicy {
    private const val AUTHORIZED = "PaymentAuthorized"
    private const val AUTHORIZATION_FAILED = "PaymentAuthorizationFailed"
    private const val EXPIRED = "PaymentExpired"
    private const val CANCELLED = "PaymentCancelled"
    private const val CANCEL_FAILED = "PaymentCancelFailed"

    /** 통지 판정 대상인 Outbox 이벤트 종류(이름은 Outbox `event_type` 그대로 — 대소문자 구분). */
    val CATALOGUED_EVENT_TYPES: Set<String> = setOf(AUTHORIZED, AUTHORIZATION_FAILED, EXPIRED, CANCELLED, CANCEL_FAILED)

    fun classify(eventType: String, paymentStatus: PaymentStatus?): MerchantNotificationDecision {
        if (eventType !in CATALOGUED_EVENT_TYPES) return MerchantNotificationDecision.NotApplicable
        val anomaly = { throw MerchantNotificationAnomalyException(eventType, paymentStatus) }
        return when (eventType) {
            AUTHORIZED -> when (paymentStatus) {
                PaymentStatus.AUTHORIZED -> MerchantNotificationDecision.Notify(MerchantNotificationType.PAYMENT_AUTHORIZED)
                PaymentStatus.FAILED, PaymentStatus.EXPIRED -> MerchantNotificationDecision.Suppress(SuppressionReason.LATE_APPROVAL_ON_TERMINATED_PAYMENT)
                else -> anomaly()
            }
            AUTHORIZATION_FAILED ->
                if (paymentStatus == PaymentStatus.FAILED) MerchantNotificationDecision.Notify(MerchantNotificationType.PAYMENT_FAILED) else anomaly()
            EXPIRED ->
                if (paymentStatus == PaymentStatus.EXPIRED) MerchantNotificationDecision.Notify(MerchantNotificationType.PAYMENT_EXPIRED) else anomaly()
            else -> when (paymentStatus) {
                PaymentStatus.AUTHORIZED -> MerchantNotificationDecision.Notify(
                    if (eventType == CANCELLED) MerchantNotificationType.PAYMENT_CANCELLED else MerchantNotificationType.PAYMENT_CANCEL_FAILED,
                )
                PaymentStatus.FAILED, PaymentStatus.EXPIRED -> MerchantNotificationDecision.Suppress(SuppressionReason.CANCELLATION_ON_TERMINATED_PAYMENT)
                else -> anomaly()
            }
        }
    }
}
