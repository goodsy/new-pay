package com.paycore.master.merchant.domain

import java.time.Instant

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
