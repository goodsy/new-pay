package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * MID(계약 계정). D-MD-003: TEST/LIVE는 Credential·요청 축이며 발급되는 MID는 모두 Production MID(계약 승인 필수, 계약 없는 체험용 식별자 없음).
 * 한 Merchant 소속(구조 §8), 발급 근거 CSA + 승인된 Revision은 불변(초기). 생명주기 ACTIVE(발급 완료)/TERMINATED(비가역, D-MD-006).
 * 발급 ≠ 개통 ≠ 결제 허용(구조 §11): ACTIVE만으로 결제가 허용되지 않는다(판정은 PAYMENT 승인 Use Case, Snapshot은 MASTER-SNAPSHOT-001).
 */
class MerchantAccount private constructor(
    val id: MerchantAccountId,
    val merchantId: MerchantId,
    val businessId: BusinessId,
    val issuingBasis: IssuingBasis,
    val lifecycle: Lifecycle,
    val issuedAt: Instant,
    val issuedBy: ActorRef,
) {
    fun requireActive() {
        if (lifecycle != Lifecycle.ACTIVE) throw MasterRuleViolation(MasterRejection.MERCHANT_ACCOUNT_NOT_ACTIVE)
    }

    override fun toString(): String = "MerchantAccount(id=${id.value}, merchantId=${merchantId.value}, lifecycle=$lifecycle)"

    companion object {
        internal fun issue(id: MerchantAccountId, merchant: Merchant, basis: IssuingBasis, at: Instant, by: ActorRef) =
            MerchantAccount(id, merchant.id, merchant.businessId, basis, Lifecycle.ACTIVE, at, by)

        /**
         * persistence Mapper 전용 복원. 발급 근거는 [IssuingBasis]가 검증한다(Revision 번호 1 이상). 소속·근거의 관계 일관성(같은 Merchant·
         * 당사자·승인 Revision)은 복합 FK가 저장 시점에 이미 강제했다.
         */
        fun reconstitute(
            id: MerchantAccountId,
            merchantId: MerchantId,
            businessId: BusinessId,
            issuingBasis: IssuingBasis,
            lifecycle: Lifecycle,
            issuedAt: Instant,
            issuedBy: ActorRef,
        ) = MerchantAccount(id, merchantId, businessId, issuingBasis, lifecycle, issuedAt, issuedBy)
    }
}

/** Merchant의 현재 Default MID(기본 계정 선택 용도 — 계약·권한·정산 조건을 상속하지 않음, 초기). PRIMARY와 다른 개념(구조 경계 §2). */
data class DefaultDesignation(
    val merchantId: MerchantId,
    val merchantAccountId: MerchantAccountId,
    val designatedAt: Instant,
    val designatedBy: ActorRef,
)

/** MID–Site 연결 역할(구조 §9): 첫 연결 PRIMARY, 추가 채널 ADDITIONAL. */
enum class SiteAssignmentRole { PRIMARY, ADDITIONAL }

/** 연결 상태(D-MD-005). 이번 범위에서 만드는 상태는 ACTIVE뿐이다(중지·종료 명령은 MASTER-LIFECYCLE-001). */
enum class SiteAssignmentStatus { ACTIVE, SUSPENDED, ENDED }

/**
 * MID–Site 연결(MerchantAccountSiteAssignment, 구조 §6–§10). MID와 Site는 같은 Merchant(구조 §8). 기간 [from, to) UTC, **소급 시작 금지**
 * (from ≥ 명령 시각), 미래 종료 예약 허용(D-MD-005). 이력은 지우지 않는다(구조 §10).
 */
class SiteAssignment private constructor(
    val id: SiteAssignmentId,
    val merchantAccountId: MerchantAccountId,
    val merchantSiteId: MerchantSiteId,
    val merchantId: MerchantId,
    val role: SiteAssignmentRole,
    val status: SiteAssignmentStatus,
    val period: EffectivePeriod,
    val createdAt: Instant,
    val createdBy: ActorRef,
) {
    /** [at]에 유효하다(ENDED가 아니고 기간 안). SUSPENDED도 기간 안이면 "유효 연결"이지만 결제 사용 가능 여부는 별도 판정이다. */
    fun isOpenAt(at: Instant): Boolean = status != SiteAssignmentStatus.ENDED && period.contains(at)

    /** 기간이 [other]와 겹치고 둘 다 ENDED가 아니다. */
    fun coexistsWith(other: SiteAssignment): Boolean =
        status != SiteAssignmentStatus.ENDED && other.status != SiteAssignmentStatus.ENDED && period.overlaps(other.period)

    override fun toString(): String = "SiteAssignment(id=${id.value}, mid=${merchantAccountId.value}, site=${merchantSiteId.value}, role=$role)"

    companion object {
        /**
         * 새 연결. [account]와 [site]는 같은 Merchant, 시작은 [now] 이후(소급 금지). 기존 연결과의 규칙은 [SiteAssignmentRules.requireCanStart].
         */
        fun start(
            id: SiteAssignmentId,
            account: MerchantAccount,
            site: MerchantSite,
            role: SiteAssignmentRole,
            period: EffectivePeriod,
            now: Instant,
            by: ActorRef,
        ): SiteAssignment {
            if (site.merchantId != account.merchantId) throw MasterRuleViolation(MasterRejection.SITE_OF_OTHER_MERCHANT)
            account.requireActive()
            site.requireActive()
            if (period.from.isBefore(now)) throw MasterRuleViolation(MasterRejection.ASSIGNMENT_RETROACTIVE)
            return SiteAssignment(id, account.id, site.id, account.merchantId, role, SiteAssignmentStatus.ACTIVE, period, now, by)
        }

        /**
         * persistence Mapper 전용 복원. 저장 상태가 domain 규칙상 존재할 수 없으면 거부한다: 소급 시작(from < 기록 시각, D-MD-005),
         * 종료 시각 없는 ENDED(ENDED = 종료 시각 확정).
         */
        @Suppress("LongParameterList")
        fun reconstitute(
            id: SiteAssignmentId,
            merchantAccountId: MerchantAccountId,
            merchantSiteId: MerchantSiteId,
            merchantId: MerchantId,
            role: SiteAssignmentRole,
            status: SiteAssignmentStatus,
            period: EffectivePeriod,
            createdAt: Instant,
            createdBy: ActorRef,
        ): SiteAssignment {
            require(!period.from.isBefore(createdAt)) { "a stored assignment cannot start before it was recorded" }
            require(status != SiteAssignmentStatus.ENDED || period.to != null) { "an ENDED assignment has an end instant" }
            return SiteAssignment(id, merchantAccountId, merchantSiteId, merchantId, role, status, period, createdAt, createdBy)
        }
    }
}

/**
 * 같은 MID의 연결 집합 규칙(호출자는 merchant 행을 잠근 상태에서 그 MID의 기존 연결 전체를 넘긴다 — MySQL에 기간 배제 제약이 없다).
 * - 같은 MID–Site 기간 중첩 금지, 재연결은 새 행(D-MD-005).
 * - 기간이 겹치는 PRIMARY는 하나(D-MD-007). 교체는 기존 종료 + 새 시작을 한 TX로(MASTER-LIFECYCLE-001), 자동 승격 없음.
 */
object SiteAssignmentRules {
    fun requireCanStart(existing: List<SiteAssignment>, candidate: SiteAssignment) {
        val sameAccount = existing.filter { it.merchantAccountId == candidate.merchantAccountId }
        require(sameAccount.size == existing.size) { "existing assignments must belong to the candidate's MID" }
        if (sameAccount.any { it.merchantSiteId == candidate.merchantSiteId && it.coexistsWith(candidate) }) {
            throw MasterRuleViolation(MasterRejection.ASSIGNMENT_PERIOD_OVERLAP)
        }
        if (candidate.role == SiteAssignmentRole.PRIMARY &&
            sameAccount.any { it.role == SiteAssignmentRole.PRIMARY && it.coexistsWith(candidate) }
        ) {
            throw MasterRuleViolation(MasterRejection.PRIMARY_ALREADY_EFFECTIVE)
        }
    }
}

/** 결제수단(초기 확정 범위: 국내 신용카드). 체크카드·할부 등은 Q-AUTH-SCOPE. */
enum class PaymentMethod { CARD }

/**
 * 개통 등록(최소 저장 — Q-MD-09 미결: 상태 집합·카드사 단위가 정해지지 않았다). MID 단위(구조 §12 Phase 1). 상태가 없으므로 이 기록은
 * **개통 완료가 아니다**: 결제 허용 판정은 개통 상태가 정해질 때까지 fail-closed로 미개통으로 본다(발급 ≠ 개통, 구조 §11).
 */
data class PaymentMethodActivation(
    val id: PaymentMethodActivationId,
    val merchantAccountId: MerchantAccountId,
    val paymentMethod: PaymentMethod,
    val registeredAt: Instant,
    val registeredBy: ActorRef,
) {
    companion object {
        fun register(id: PaymentMethodActivationId, account: MerchantAccount, method: PaymentMethod, at: Instant, by: ActorRef) =
            account.requireActive().let { PaymentMethodActivation(id, account.id, method, at, by) }
    }
}
