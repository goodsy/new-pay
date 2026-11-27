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
         * 새 가맹점 등록(B1, MERCHANT-CODE-001 §3.3 + D-BIZ-001 + MERCHANT-FLOW-001 FLOW-3 §4B.8). 판정 순서: 사업자 생명주기(`BUSINESS_NOT_ACTIVE`) → 등록 상태(`BUSINESS_NOT_REGISTERED`) →
         * **사업자 계약 관문**([requireEffective] — `BUSINESS_CONTRACT_NOT_EFFECTIVE`) → 코드 예약어(`MERCHANT_CODE_RESERVED`). 코드는 필수이고 seq 1 `REGISTRATION` 배정 하나를 만든다
         * (중복 = Application 사전 조회 + DB UNIQUE). [contract] = 사업자 행 잠금 뒤 같은 TX에서 읽은 그 사업자의 최신 사업자 계약(없으면 null). 접수 건은 입력이 아니다(BR-BIZ-319).
         * 기존 가맹점의 Site·계약·MID·개통 명령은 사업자 등록 상태·사업자 계약을 보지 않는다(D-BIZ-001 "기존 무영향", BR-BIZ-340).
         */
        @Suppress("LongParameterList")
        fun register(id: MerchantId, business: Business, contract: BusinessContract?, displayName: DisplayName, code: MerchantCode, at: Instant, by: ActorRef): Merchant {
            business.requireActive()
            business.requireRegistered()
            requireEffective(business, contract, at)
            code.requireAssignable()
            return Merchant(id, business.id, displayName, Lifecycle.ACTIVE, 0, at, by, listOf(MerchantCodeAssignment.registration(code, at, by)))
        }

        /**
         * FLOW-3 관문(BR-BIZ-320·327·328): 최신 사업자 계약이 판정 시각에 유효(`statusAt` — fail-closed, 2a MAJOR-1)이고 적용 중 버전(판정 시각 = `judgedAt`) 범위에 카드 PG 결제가
         * 있어야 한다. 그 밖(없음·효력 전·정지·해지·만료·범위 없음)은 `BUSINESS_CONTRACT_NOT_EFFECTIVE` 하나. 통과하면 근거 버전을 돌려준다(등록 근거 기록 — BR-BIZ-337).
         * 읽기 메서드만 부른다(ArchUnit ③ — 계약 변경 메서드 호출 없음).
         */
        fun requireEffective(business: Business, contract: BusinessContract?, at: Instant): BusinessContractVersion = requireEffectiveFor(business.id, contract, at)

        /** [requireEffective]의 사업자 ID 판(등록 근거 계산용 — 같은 식). */
        fun requireEffectiveFor(businessId: BusinessId, contract: BusinessContract?, at: Instant): BusinessContractVersion {
            require(contract == null || contract.id.businessId == businessId) { "the contract belongs to another business" }
            if (contract == null || contract.statusAt(at) != BusinessContractStatus.ACTIVE) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_NOT_EFFECTIVE)
            val applying = contract.applyingVersionAt(contract.judgedAt(at))
            if (applying == null || ServiceKind.CARD_PG_PAYMENT !in applying.services) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_NOT_EFFECTIVE)
            return applying
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
