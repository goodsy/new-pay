package com.paycore.payment.entitlement.application.required

import com.paycore.payment.entitlement.application.provided.FreshnessOverview
import com.paycore.payment.entitlement.domain.EntitlementContent
import com.paycore.payment.entitlement.domain.EntitlementHead
import com.paycore.payment.entitlement.domain.InboxOutcome
import java.time.Duration
import java.time.Instant

/*
 * PAYMENT 권리 투영 Port(MASTER-SNAPSHOT-001 명세 §5). 모든 저장 메서드는 application이 연 PAYMENT 로컬 TX 안에서만 동작하고(구현은 TX 없으면
 * 거부) 쓰기 끝에 flush해 제약 위반을 [EntitlementPersistenceFailure]로 드러낸다. 잠금 순서(PAYMENT 전체): idempotency_request →
 * entitlement_head(S: CreatePayment 판정 / X: Inbox) → order_payment_control → payment. Inbox는 control·payment를 잡지 않는다.
 */

/** 저장된 Inbox 기록(같은 eventId 재전송 판정용). */
data class StoredInbox(val outcome: InboxOutcome, val payloadSha256: String)

/** Inbox 기록 한 건(append-only 수신 증거·재처리 원문). [payload]는 받은 봉투 본문, [payloadSha256]은 그 원문 바이트의 SHA-256. */
data class InboxRecord(
    val consumer: String,
    val eventId: String,
    val eventType: String,
    val schemaVersion: Int,
    val aggregateId: String,
    val aggregateVersion: Long,
    val outcome: InboxOutcome,
    val headRevisionAfter: Long,
    val payload: String,
    val payloadSha256: String,
    val receivedAt: Instant,
)

interface EntitlementProjectionStore {
    /** head 자리를 만들고(없으면 revision 0 — `INSERT … ON DUPLICATE KEY UPDATE` no-op) `SELECT … FOR UPDATE`로 잠가 읽는다(Inbox). */
    fun lockHeadForUpdate(merchantId: String): EntitlementHead

    /** `SELECT … FOR SHARE`(CreatePayment 판정 — 진행 중 Inbox 적용(X)과 직렬화된다, 명세 §3.4-4). 없으면 null. */
    fun lockHeadForShare(merchantId: String): EntitlementHead?

    fun findInbox(consumer: String, eventId: String): StoredInbox?

    fun insertInbox(record: InboxRecord)

    /** revision별 내용(append-only). 정규 JSON 그대로 저장하고 읽을 때 hash를 다시 확인한다. */
    fun insertSnapshot(content: EntitlementContent, schemaVersion: Int)

    fun findSnapshot(merchantId: String, revision: Long): EntitlementContent?

    /** 새 MID의 소속을 기록한다(MID 소속은 불변 — 이미 있으면 같은 Merchant인지 확인만). head 잠금 아래에서 부른다. */
    fun recordMidOwners(merchantId: String, mids: Collection<String>, at: Instant)

    fun findMidOwner(mid: String): String?

    /** 잠근 head를 [head]로 바꾼다(revision은 줄지 않는다). */
    fun updateHead(head: EntitlementHead, appliedAt: Instant)
}

/** PAYMENT 서버 시각(판정 now·수신 시각). 시험은 대역으로 고정한다. */
interface EntitlementClock {
    fun now(): Instant
}

/** 판정 설정(명세 §8). 운영 기본값 없음 — 구현은 값이 없으면 기동을 실패시킨다. */
interface EntitlementTiming {
    /** MASTER·PAYMENT 시계 오차 여유. `t ≥ leaseUntil − margin`이면 만료(Demo 2초, 시험 100ms — 운영 미결). */
    val clockSkewMargin: Duration
}

/** PAYMENT 권리 지표(명세 §11). label은 분류값만(outcome·결과 종류·reason), MID·merchantId 금지. */
interface EntitlementMetrics {
    fun inboxOutcome(outcome: InboxOutcome)

    fun inboxRejected(kind: String)

    fun eligibility(result: String, reason: String?)
}

/** 신선도 조회(jOOQ adapter.query). */
interface EntitlementFreshnessReader {
    fun overview(now: Instant): FreshnessOverview
}

/**
 * 저장 Port 기술 실패(entitlement 모듈 — 다른 모듈의 required 타입을 쓰지 않는다, Modulith 공개 범위). 분류는 payment 모듈과 같은 관례:
 * 충돌(UNIQUE·version)·교착·잠금 timeout·연결 실패. 메시지에 키 값을 넣지 않는다.
 */
sealed class EntitlementPersistenceFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    class UniqueConflict(val constraint: String) : EntitlementPersistenceFailure("unique constraint conflict: $constraint", null)

    class OptimisticConflict(val entity: String) : EntitlementPersistenceFailure("concurrent modification of $entity", null)

    class Deadlock(cause: Throwable) : EntitlementPersistenceFailure("deadlock (transaction rolled back by the database)", cause)

    class LockTimeout(cause: Throwable) : EntitlementPersistenceFailure("lock wait timeout", cause)

    class Unavailable(cause: Throwable) : EntitlementPersistenceFailure("database unavailable", cause)
}
