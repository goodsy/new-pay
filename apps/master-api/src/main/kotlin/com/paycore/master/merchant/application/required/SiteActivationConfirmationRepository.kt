package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SiteActivationConfirmation
import com.paycore.master.merchant.domain.SiteActivationConfirmationId

/** Site 적용 확인(append-only)과 철회(append-only, 확인당 하나). */
interface SiteActivationConfirmationRepository {
    fun insert(confirmation: SiteActivationConfirmation, requestRecordId: String)

    fun find(id: SiteActivationConfirmationId): SiteActivationConfirmation?

    fun listByActivation(activationId: PaymentMethodActivationId): List<SiteActivationConfirmation>

    fun listByAccount(accountId: MerchantAccountId): List<SiteActivationConfirmation>

    fun insertRevocation(confirmation: SiteActivationConfirmation, requestRecordId: String)
}
