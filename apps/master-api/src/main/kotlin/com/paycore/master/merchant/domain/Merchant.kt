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
    val codeAssignments: List<MerchantCodeAssignment>,
) {
    var entitlementRevision: Long = entitlementRevision
        private set

    init {
        require(entitlementRevision >= 0) { "entitlementRevision must not be negative" }
        // MERCHANT-CODE-001 §3.2: 모든 가맹점은 seq 1 배정을 갖고(없으면 결함 — 등록 TX·V13 채우기가 보장), 최대 1회 변경(seq 2)은 같은 가맹점 seq 1 코드를 바꾼다.
        // 위반은 입력 오류가 아니라 저장 상태의 결함 신호다 → IllegalState(check — §3.2 "앱 읽기는 seq 1 없음 = 결함").
        check(codeAssignments.size in 1..MerchantCodeAssignment.CHANGE && codeAssignments.map { it.seq } == (1..codeAssignments.size).toList()) {
            "a merchant has a seq-1 code assignment and at most one change"
        }
        check(codeAssignments.size < MerchantCodeAssignment.CHANGE || codeAssignments[1].replacesCode == codeAssignments[0].code) {
            "the change replaces the first assignment of the same merchant"
        }
    }

    /** 현재 코드 = 가장 큰 seq의 코드(MERCHANT-CODE-001 §3.2). 은퇴 코드는 [codeAssignments]에 남는다. */
    val currentCode: MerchantCode get() = codeAssignments.last().code

    fun requireActive() {
        if (lifecycle != Lifecycle.ACTIVE) throw MasterRuleViolation(MasterRejection.MERCHANT_NOT_ACTIVE)
    }

    /** 결제 허용 판정 입력이 바뀌었다(같은 TX에서 저장). */
    fun recordEntitlementChange() {
        entitlementRevision += 1
    }

    override fun toString(): String = "Merchant(id=${id.value}, lifecycle=$lifecycle, entitlementRevision=$entitlementRevision)"

    companion object {
        /** B1 등록(MERCHANT-CODE-001 §3.3): 코드 필수 — 예약어면 `MERCHANT_CODE_RESERVED`, seq 1 `REGISTRATION` 배정 하나. 중복(배정 이력 존재)은 Application 사전 조회 + DB UNIQUE. */
        fun register(id: MerchantId, business: Business, displayName: DisplayName, code: MerchantCode, at: Instant, by: ActorRef): Merchant {
            business.requireActive()
            code.requireAssignable()
            return Merchant(id, business.id, displayName, Lifecycle.ACTIVE, 0, at, by, listOf(MerchantCodeAssignment.registration(code, at, by)))
        }

        @Suppress("LongParameterList")
        fun reconstitute(
            id: MerchantId,
            businessId: BusinessId,
            displayName: DisplayName,
            lifecycle: Lifecycle,
            entitlementRevision: Long,
            createdAt: Instant,
            createdBy: ActorRef,
            codeAssignments: List<MerchantCodeAssignment>,
        ) = Merchant(id, businessId, displayName, lifecycle, entitlementRevision, createdAt, createdBy, codeAssignments)
    }
}
