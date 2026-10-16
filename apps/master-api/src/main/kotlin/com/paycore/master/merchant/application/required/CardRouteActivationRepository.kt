package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.ActivationTransition
import com.paycore.master.merchant.domain.CardRouteActivation
import com.paycore.master.merchant.domain.CardRouteActivationId
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MerchantAccountId

interface CardRouteActivationRepository {
    fun find(id: CardRouteActivationId): Versioned<CardRouteActivation>?

    fun lock(id: CardRouteActivationId): Versioned<CardRouteActivation>?

    fun listByAccount(accountId: MerchantAccountId): List<CardRouteActivation>

    /** 그 개통 전이 이력(append-only)에 남은 근거 참조 전체(재개 근거 "새 것" 판정). */
    fun evidenceHistory(id: CardRouteActivationId): List<EvidenceRef>

    fun insert(route: CardRouteActivation, transition: ActivationTransition, requestRecordId: String)

    fun update(route: CardRouteActivation, expectedVersion: Long, transition: ActivationTransition, requestRecordId: String)
}
