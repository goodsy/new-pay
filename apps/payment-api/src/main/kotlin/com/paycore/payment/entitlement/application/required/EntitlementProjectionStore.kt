package com.paycore.payment.entitlement.application.required

import com.paycore.payment.entitlement.domain.EntitlementContent
import com.paycore.payment.entitlement.domain.EntitlementHead
import com.paycore.payment.entitlement.domain.InboxOutcome
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

    /**
     * 잠그지 않는 head 읽기(MERCHANT-CODE-001 MC-4 — 가맹점 코드 출력 전용 조회). 판정·적용에는 쓰지 않는다(판정은 [lockHeadForShare], 적용은 [lockHeadForUpdate]).
     * 없으면 null.
     */
    fun findHead(merchantId: String): EntitlementHead?

    /** 잠근 head를 [head]로 바꾼다(revision은 줄지 않는다). */
    fun updateHead(head: EntitlementHead, appliedAt: Instant)
}
