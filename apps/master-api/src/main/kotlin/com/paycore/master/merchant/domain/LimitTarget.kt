package com.paycore.master.merchant.domain

/**
 * 한도 정책 대상 확인(소속 검사를 domain에서 한 번만). CSA 기본: 계약이 그 Merchant 것이고 CSA가 그 계약에 있다. MID Override: MID가 그 Merchant
 * 것이고 ACTIVE다.
 */
class LimitTarget private constructor(val merchantId: MerchantId, val scope: LimitScope) {
    companion object {
        fun csaDefault(merchant: Merchant, contract: Contract, agreementId: ContractServiceAgreementId): LimitTarget {
            merchant.requireActive()
            if (contract.merchantId != merchant.id) throw MasterRuleViolation(MasterRejection.CONTRACT_MERCHANT_MISMATCH)
            contract.agreement(agreementId) ?: throw MasterRuleViolation(MasterRejection.AGREEMENT_NOT_FOUND)
            return LimitTarget(merchant.id, LimitScope.CsaDefault(agreementId))
        }

        fun midOverride(merchant: Merchant, account: MerchantAccount): LimitTarget {
            merchant.requireActive()
            require(account.merchantId == merchant.id) { "MID must belong to the merchant" }
            account.requireActive()
            return LimitTarget(merchant.id, LimitScope.MidOverride(account.id))
        }
    }
}
