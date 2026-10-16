package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.domain.MerchantId

interface MerchantRepository {
    fun insert(merchant: Merchant)

    fun lock(id: MerchantId): Versioned<Merchant>?

    /** 잠금 없는 읽기(소속 Business 같은 불변 값을 먼저 알아 잠금 순서를 지키는 데 쓴다). */
    fun find(id: MerchantId): Merchant?

    /** entitlement revision 반영. 기대 version이 다르면 [PersistenceFailure.OptimisticConflict]. 새 version을 돌려준다. */
    fun update(merchant: Merchant, expectedVersion: Long): Long
}
