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
