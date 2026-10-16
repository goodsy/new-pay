package com.paycore.payment.entitlement.domain

/** head를 막은 사유(명세 §3.5). 막힌 head로는 판정하지 않는다(Unavailable VERSION_UNSUPPORTED). */
enum class HeadBlock { VERSION_UNSUPPORTED, INTEGRITY_CONFLICT }

/** 수신 판정 결과(Inbox outcome — 저장·응답 값, 명세 §3.5). DUPLICATE는 application이 Inbox 조회로 정한다. */
enum class InboxOutcome { APPLIED, LEASE_EXTENDED, STALE, DUPLICATE, INTEGRITY_CONFLICT, UNSUPPORTED }

/**
 * Merchant별 현재 적용 상태(PAYMENT `entitlement_head`). revision 0 = "아직 없음"(IODKU로 만든 자리). [blockedRevision]은 막은 Snapshot의 revision
 * (지원하지 않는 더 새 버전, 또는 같은 revision·다른 내용).
 */
data class EntitlementHead(
    val merchantId: String,
    val revision: Long,
    val contentHash: String?,
    val schemaVersion: Int?,
    val lease: Lease?,
    val sourceEventId: String?,
    val blocked: HeadBlock?,
    val blockedRevision: Long?,
) {
    init {
        require(revision >= 0) { "head revision must not be negative" }
        require((revision == 0L) == (contentHash == null && lease == null && schemaVersion == null)) { "an empty head has no content; a filled head has all" }
        require((blocked == null) == (blockedRevision == null)) { "block reason and revision are recorded together" }
    }

    val isEmpty: Boolean get() = revision == 0L

    /** 지금까지 본 가장 큰 revision(적용했거나 막은 것). 이 값 이하의 새 내용은 되돌릴 수 없다(R2). */
    val highestSeen: Long get() = maxOf(revision, blockedRevision ?: 0)

    companion object {
        fun empty(merchantId: String) = EntitlementHead(merchantId, 0, null, null, null, null, null, null)
    }
}
