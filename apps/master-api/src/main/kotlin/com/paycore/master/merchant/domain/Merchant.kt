package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 가맹점. Business 1:N, Merchant 1:N MerchantSite·MID(구조 §13). Merchant 안 관계 변경(최초 Default 발급·개통 등)의 직렬화 지점이다
 * (application이 이 행을 먼저 잠근다).
 *
 * [entitlementRevision]: 결제 허용 판정 입력(MID 발급·Site 연결·개통·계약 승인)이 바뀔 때마다 +1하는 단조 값(관례 — MASTER-SNAPSHOT-001이
 * 차단 버전을 과거 허용 이벤트로 되돌리지 않도록 버전 비교에 쓴다, 허용 §4.2). JPA version(동시 수정 감지 기술 값)과 별개다.
 */
class Merchant private constructor(
    val id: MerchantId,
    val businessId: BusinessId,
    val displayName: DisplayName,
    val lifecycle: Lifecycle,
    entitlementRevision: Long,
    val createdAt: Instant,
    val createdBy: ActorRef,
) {
    var entitlementRevision: Long = entitlementRevision
        private set

    init {
        require(entitlementRevision >= 0) { "entitlementRevision must not be negative" }
    }

    fun requireActive() {
        if (lifecycle != Lifecycle.ACTIVE) throw MasterRuleViolation(MasterRejection.MERCHANT_NOT_ACTIVE)
    }

    /** 결제 허용 판정 입력이 바뀌었다(같은 TX에서 저장). */
    fun recordEntitlementChange() {
        entitlementRevision += 1
    }

    override fun toString(): String = "Merchant(id=${id.value}, lifecycle=$lifecycle, entitlementRevision=$entitlementRevision)"

    companion object {
        fun register(id: MerchantId, business: Business, displayName: DisplayName, at: Instant, by: ActorRef): Merchant {
            business.requireActive()
            return Merchant(id, business.id, displayName, Lifecycle.ACTIVE, 0, at, by)
        }

        fun reconstitute(
            id: MerchantId,
            businessId: BusinessId,
            displayName: DisplayName,
            lifecycle: Lifecycle,
            entitlementRevision: Long,
            createdAt: Instant,
            createdBy: ActorRef,
        ) = Merchant(id, businessId, displayName, lifecycle, entitlementRevision, createdAt, createdBy)
    }
}
