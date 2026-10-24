package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentStatus
import java.time.Instant

/** fan-out 후보(비잠금 읽기). [paymentKey]는 같은 Payment의 뒤 행을 막는 데 쓴다(payload `paymentId`, 없으면 null). */
data class OutboxNotificationCandidate(val outboxId: Long, val paymentKey: String?)

/** 알림 대상 Payment의 현재 행 값(불변 생성 금액 + 상태). */
data class NotifiedPayment(val status: PaymentStatus, val amountMinor: Long, val currency: String)

/**
 * 통지 판정 대상 이벤트의 주체. [payment]가 null이면 Payment 행을 읽지 못했다(이상 — fan-out이 실패로 둔다).
 */
data class NotificationSubject(
    val environment: Environment,
    val merchantAccountId: MerchantAccountId,
    val paymentId: PaymentId,
    val merchantOrderId: MerchantOrderId,
    val payment: NotifiedPayment?,
    val facts: MerchantNotificationFacts,
)

/** 아직 종결되지 않은 Outbox 행 하나. [subject]는 통지 판정 대상 종류(5종)일 때만 있다. */
data class PendingOutboxNotification(
    val outboxId: Long,
    val sourceEventId: String,
    val eventType: String,
    val occurredAt: Instant,
    val subject: NotificationSubject?,
)

/** Outbox 행의 종결 표시(rev 0.2 — `HELD_DECISION` 없음, H-1). 비종결 실패는 [MerchantNotificationFanoutStore.markFailed]. */
enum class OutboxNotificationMark { NOTIFIED, SUPPRESSED, NOT_APPLICABLE }

/** 통지 대상 이벤트의 payload·관련 행을 읽을 수 없다(형식 오류·필드 누락). [code]는 고정 분류 코드. */
class UnreadableNotificationSourceException(val code: String) : RuntimeException("merchant notification source unreadable: $code")

/**
 * fan-out Outbox 처리 저장 Port(WEBHOOK-001 §5.0 rev 0.2, CONVENTIONS §14 Store ③ 프로토콜 기록). [candidates]는 자기 읽기 전용 TX의 비잠금 읽기, 나머지는 호출자 TX 필수(MANDATORY).
 * 한 행 TX: [lockGuard](fan-out 쓰기 직렬화) → [load] → … → [mark]. Outbox는 잠금 읽기하지 않고 PK로 `merchant_notification_*` 열만 바꾼다(업무 TX의 INSERT가 기다리지 않는다 — H-3).
 */
interface MerchantNotificationFanoutStore {
    /** PENDING 또는 재시도 시각이 된 FANOUT_FAILED 행을 Outbox `id` 순으로(같은 Payment에 더 앞선 미종결 실패 행이 있으면 제외). */
    fun candidates(now: Instant, limit: Int): List<OutboxNotificationCandidate>

    fun lockGuard()

    /**
     * 같은 Payment([paymentKey] = payload paymentId)에 이 행보다 앞선(id가 작은) 미종결 행(PENDING·FANOUT_FAILED)이 있는가(검토 #1 H-1). guard를 잡은 뒤 부른다 — 후보를 읽은 뒤 다른 인스턴스가
     * 앞 행을 실패로 커밋했어도 뒤 행을 먼저 통지하지 않는다(sequence = 인과 순서). paymentId를 읽을 수 없는 payload는 이 검사로 막을 수 없다(n-3 — 기록된 한계).
     */
    fun hasEarlierOpenRow(outboxId: Long, paymentKey: String): Boolean

    /** 그 행이 아직 처리 대상이면(종결 안 됨, 실패면 재시도 시각 도래) 읽어 해석한다. 아니면 null. 해석 불가는 [UnreadableNotificationSourceException]. */
    fun load(outboxId: Long, now: Instant): PendingOutboxNotification?

    fun mark(outboxId: Long, mark: OutboxNotificationMark, at: Instant)

    /** 비종결 실패(FANOUT_FAILED): 실패 코드·횟수 +1·재시도 시각. 종결된 행은 바꾸지 않는다. */
    fun markFailed(outboxId: Long, failureCode: String, at: Instant, retryAt: Instant)

    /** 그 Payment 가맹점 이벤트 다음 순번(1부터 연속). */
    fun nextSequence(paymentId: PaymentId): Int
}
