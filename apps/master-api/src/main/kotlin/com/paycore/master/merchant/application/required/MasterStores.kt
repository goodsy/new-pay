package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.Contract
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.FirstDefaultIssuance
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.domain.MerchantAccount
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSite
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PaymentMethodActivation
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SiteAssignment
import com.paycore.master.merchant.domain.SiteChannel

/*
 * MASTER 저장 Port(application.required). 모든 메서드는 application이 연 MASTER 로컬 TX 안에서만 동작한다(구현은 TX 없으면 거부).
 * 쓰기 메서드는 Port 안에서 flush해 제약 위반을 [PersistenceFailure]로 드러낸다(commit 시점이 아니라).
 * lock* = `SELECT … FOR UPDATE`(잠금 순서: admin_request → business → merchant → contract → merchant_account, 명세 §3.3).
 */

/** 저장된 값과 그 행의 JPA version(동시 수정 감지 기술 값). 갱신 Port는 기대 version을 받아 오래된 domain으로 덮어쓰지 않는다. */
data class Versioned<T>(val value: T, val version: Long)

interface BusinessStore {
    fun insert(business: Business)

    fun lock(id: BusinessId): Business?

    /** 같은 등록번호의 기존 Business(D-MD-008 중복 판정). 잠금 없음 — 동시 등록 경합은 UNIQUE 충돌 → 재판정으로 수렴한다. */
    fun findIdByRegistrationNumber(number: BusinessRegistrationNumber): BusinessId?
}

interface MerchantStore {
    fun insert(merchant: Merchant)

    fun lock(id: MerchantId): Versioned<Merchant>?

    /** 잠금 없는 읽기(소속 Business 같은 불변 값을 먼저 알아 잠금 순서를 지키는 데 쓴다). */
    fun find(id: MerchantId): Merchant?

    /** entitlement revision 반영. 기대 version이 다르면 [PersistenceFailure.OptimisticConflict]. 새 version을 돌려준다. */
    fun update(merchant: Merchant, expectedVersion: Long): Long
}

interface MerchantSiteStore {
    fun insert(site: MerchantSite)

    fun find(id: MerchantSiteId): MerchantSite?

    /** 같은 채널 식별값의 활성 Site(어느 Merchant든, D-MD-008). 잠금 없음 — 경합은 UNIQUE 충돌 → 재판정. */
    fun findActiveByChannel(channel: SiteChannel): MerchantSiteId?
}

interface ContractStore {
    /** 계약과 Revision 1(Scope Site 포함)을 넣고 계약 행 version을 돌려준다. */
    fun insertNew(contract: Contract): Long

    /** 잠금 없는 읽기(Revision 내용은 불변이라 Scope Merchant를 먼저 알아 잠금 순서를 지키는 데 쓴다). */
    fun find(id: ContractId): Contract?

    fun lock(id: ContractId): Versioned<Contract>?

    /** 새 Revision·승인 사실·CSA만 추가한다(기존 자식 행은 바꾸지 않는다 — append-only). 계약 행 version을 올리고 돌려준다. */
    fun update(contract: Contract, expectedVersion: Long): Long
}

interface MerchantAccountStore {
    fun countByMerchant(merchantId: MerchantId): Long

    fun findDefault(merchantId: MerchantId): MerchantAccountId?

    /** 최초 Default 발급의 MID·Default 지정·PRIMARY 연결을 이 TX에 넣는다(구조 경계 §1). */
    fun insertFirstDefault(issuance: FirstDefaultIssuance)

    fun find(id: MerchantAccountId): MerchantAccount?

    fun lock(id: MerchantAccountId): MerchantAccount?

    fun assignmentsOf(id: MerchantAccountId): List<SiteAssignment>
}

interface PaymentMethodActivationStore {
    fun find(accountId: MerchantAccountId, method: PaymentMethod): PaymentMethodActivationId?

    fun insert(activation: PaymentMethodActivation)
}
