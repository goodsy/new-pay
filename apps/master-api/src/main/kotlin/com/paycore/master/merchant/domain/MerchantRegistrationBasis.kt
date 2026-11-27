package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 가맹점 등록 근거 기록(MERCHANT-FLOW-001 §4B.2·§4B.8 — BR-BIZ-337, 표 `merchant_registration_basis`, INSERT 전용 — 저장 Port `MerchantRegistrationBasisStore`, Aggregate 아님):
 * 새 가맹점이 등록될 때 근거가 된 사업자 계약 ID([contractId])·적용 중 버전 번호([versionNo])·기록 시각(lockedNow)·행위자. **기록 시점 사실** — 뒤의 정지·해지·만료·새 버전에 바뀌지
 * 않는다. 판정·권리·Snapshot·Outbox에 쓰지 않는다(읽는 경로는 화면 조회뿐 — ArchUnit ④).
 */
data class MerchantRegistrationBasis(val merchantId: MerchantId, val contractId: BusinessContractId, val versionNo: Int, val recordedAt: Instant, val recordedBy: ActorRef) {
    init {
        require(versionNo >= 1) { "version numbers start at 1" }
    }

    companion object {
        /** 등록 판정과 같은 관문 식([Merchant.requireEffective])으로 근거 버전을 정한다 — 같은 TX·같은 판정 시각이면 같은 결과. */
        fun of(merchant: Merchant, contract: BusinessContract, at: Instant, by: ActorRef): MerchantRegistrationBasis {
            require(merchant.businessId == contract.id.businessId) { "the contract belongs to another business" }
            val version = Merchant.requireEffectiveFor(merchant.businessId, contract, at)
            return MerchantRegistrationBasis(merchant.id, contract.id, version.versionNo, at, by)
        }
    }
}
