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
    codeAssignments: List<MerchantCodeAssignment>,
) {
    private val assignments: MutableList<MerchantCodeAssignment> = codeAssignments.toMutableList()

    /** 배정 이력(seq 오름차순 — 최대 2). 변경은 [changeCodeWithEvidence]가 seq 2를 덧붙이는 것뿐이다. */
    val codeAssignments: List<MerchantCodeAssignment> get() = assignments.toList()

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
    val currentCode: MerchantCode get() = assignments.last().code

    /**
     * MERCHANT-CODE-001 MC-1b(§3.7.3·§3.7.4 — D-MCODE-002 Q-MC-7): 가맹점 코드의 **유일한 변경 메서드**(근거 있는 2인 명령 전용 — 근거 없는 변경 메서드는 없다, C-11).
     * 판정 순서 7 → 8 → 9 → 10 → 11(→ 12의 자기 코드 부분): 비활성 `MERCHANT_NOT_ACTIVE` → 이미 1회 변경 `MERCHANT_CODE_ALREADY_CHANGED` → 첫 계약 승인 뒤 `MERCHANT_CODE_LOCKED`
     * → [expectedCurrent] ≠ 현재 코드 `MERCHANT_CODE_MISMATCH`(결함 신호) → 예약어 `MERCHANT_CODE_RESERVED` → 이 가맹점의 배정 코드(현재 코드 포함)와 같음 `MERCHANT_CODE_TAKEN`.
     * 다른 가맹점·은퇴 코드 중복(판정 12)은 Application 사전 조회 + DB `UNIQUE(merchant_code)`가 본다.
     * [contractApproved]는 Application이 merchant 잠금 **뒤** 읽은 "그 가맹점 대상 계약에 승인 사실 행 있음"(§3.7.1 — Domain은 계약 Aggregate를 모른다).
     * [approver]는 seq 2 `assigned_by` 기록용이며 **판정 입력이 아니다**(D-MCODE-002 Q-MC-10 — 원 가맹점 등록자 [createdBy]와 비교하지 않는다. 요청자 ≠ 승인자는 실행기 공통 판정 1).
     */
    @Suppress("LongParameterList")
    fun changeCodeWithEvidence(
        newCode: MerchantCode,
        expectedCurrent: MerchantCode,
        contractApproved: Boolean,
        reason: MerchantCodeChangeReason,
        approvalId: String,
        at: Instant,
        approver: ActorRef,
    ): MerchantCodeAssignment {
        requireActive()
        if (assignments.size >= MerchantCodeAssignment.CHANGE) throw MasterRuleViolation(MasterRejection.MERCHANT_CODE_ALREADY_CHANGED)
        if (contractApproved) throw MasterRuleViolation(MasterRejection.MERCHANT_CODE_LOCKED)
        if (expectedCurrent != currentCode) throw MasterRuleViolation(MasterRejection.MERCHANT_CODE_MISMATCH)
        newCode.requireAssignable()
        if (assignments.any { it.code == newCode }) throw MasterRuleViolation(MasterRejection.MERCHANT_CODE_TAKEN)
        val change = MerchantCodeAssignment.change(newCode, currentCode, approvalId, reason, at, approver)
        assignments += change
        return change
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
        /**
         * B1 등록(MERCHANT-CODE-001 §3.3): 코드 필수 — 예약어면 `MERCHANT_CODE_RESERVED`, seq 1 `REGISTRATION` 배정 하나. 중복(배정 이력 존재)은 Application 사전 조회 + DB UNIQUE.
         * BUSINESS-PROFILE-001 C-27 전환(D-BIZ-001): 새 가맹점은 REGISTERED 사업자만 — 판정 순서 `BUSINESS_NOT_ACTIVE` → `BUSINESS_NOT_REGISTERED` → 코드 예약어(§4.4).
         * 기존 가맹점의 Site·계약·MID·개통 명령은 사업자 등록 상태를 보지 않는다(D-BIZ-001 "기존 무영향").
         */
        fun register(id: MerchantId, business: Business, displayName: DisplayName, code: MerchantCode, at: Instant, by: ActorRef): Merchant {
            business.requireActive()
            business.requireRegistered()
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
