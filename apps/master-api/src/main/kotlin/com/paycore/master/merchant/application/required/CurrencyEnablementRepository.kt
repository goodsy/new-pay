package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.CurrencyEnablement
import com.paycore.master.merchant.domain.CurrencyEnablementId
import com.paycore.master.merchant.domain.CurrencyUnit

/** 통화 단위 기준정보와 통화 활성화 기록. */
interface CurrencyEnablementRepository {
    fun findUnit(code: CurrencyCode): CurrencyUnit?

    fun find(id: CurrencyEnablementId): Versioned<CurrencyEnablement>?

    fun lock(id: CurrencyEnablementId): Versioned<CurrencyEnablement>?

    fun listByAccount(accountId: MerchantAccountId): List<CurrencyEnablement>

    fun insert(enablement: CurrencyEnablement, requestRecordId: String)

    fun update(enablement: CurrencyEnablement, expectedVersion: Long)
}
