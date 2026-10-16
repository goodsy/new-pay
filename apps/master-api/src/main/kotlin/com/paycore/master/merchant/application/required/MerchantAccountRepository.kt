package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.FirstDefaultIssuance
import com.paycore.master.merchant.domain.MerchantAccount
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.SiteAssignment

interface MerchantAccountRepository {
    fun countByMerchant(merchantId: MerchantId): Long

    fun findDefault(merchantId: MerchantId): MerchantAccountId?

    /** 최초 Default 발급의 MID·Default 지정·PRIMARY 연결을 이 TX에 넣는다(구조 경계 §1). */
    fun insertFirstDefault(issuance: FirstDefaultIssuance)

    fun find(id: MerchantAccountId): MerchantAccount?

    fun lock(id: MerchantAccountId): MerchantAccount?

    fun assignmentsOf(id: MerchantAccountId): List<SiteAssignment>

    fun listByMerchant(merchantId: MerchantId): List<MerchantAccount>
}
