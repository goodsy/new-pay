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
import com.paycore.master.merchant.domain.ActivationTransition
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.CurrencyEnablement
import com.paycore.master.merchant.domain.CurrencyEnablementId
import com.paycore.master.merchant.domain.CurrencyUnit
import com.paycore.master.merchant.domain.SiteActivationConfirmation
import com.paycore.master.merchant.domain.SiteActivationConfirmationId
import com.paycore.master.merchant.domain.SiteReviewDecision

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

    /** 잠금 없는 읽기(Snapshot 조립 — Business 상태 변경 명령은 그 Business의 Merchant를 모두 잠근다, 명세 §3.2). */
    fun find(id: BusinessId): Business?
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

    fun listByMerchant(merchantId: MerchantId): List<MerchantSite>
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

    fun listByMerchant(merchantId: MerchantId): List<MerchantAccount>
}

/**
 * 개통(D-MD-017, D-MS-003). 현재 상태는 `payment_method_activation`(UPDATE, `@Version`), 전이 이력은 append-only `payment_method_activation_event`.
 * 쓰기 메서드는 상태 변경과 이력 한 건을 같은 TX에 넣는다([requestRecordId] = 관리 명령 요청 기록 ID).
 */
interface PaymentMethodActivationStore {
    /** 같은 (MID, 수단)의 TERMINATED가 아닌 행(하나 — DB 생성 컬럼 UNIQUE). */
    fun findOpen(accountId: MerchantAccountId, method: PaymentMethod): Versioned<PaymentMethodActivation>?

    /** 잠금 없는 읽기(소속 MID를 먼저 알아 잠금 순서를 지키는 데 쓴다). */
    fun find(id: PaymentMethodActivationId): Versioned<PaymentMethodActivation>?

    /** `SELECT … FOR UPDATE`로 최신 커밋 값을 다시 읽는다(merchant·merchant_account 잠금 뒤). */
    fun lock(id: PaymentMethodActivationId): Versioned<PaymentMethodActivation>?

    fun listByAccount(accountId: MerchantAccountId): List<PaymentMethodActivation>

    /** 이 Activation 전이 이력에 남은 모든 기관 근거 참조(append-only 이력 기준 — 재개 "새 근거" 판정, review m-2). */
    fun evidenceHistory(id: PaymentMethodActivationId): List<com.paycore.master.merchant.domain.EvidenceRef>

    fun insert(activation: PaymentMethodActivation, transition: ActivationTransition, requestRecordId: String)

    /** 상태 반영 + 이력 append. 기대 version이 다르면 [PersistenceFailure.OptimisticConflict]. */
    fun update(activation: PaymentMethodActivation, expectedVersion: Long, transition: ActivationTransition, requestRecordId: String)
}

/** Site 적용 확인(append-only)과 철회(append-only, 확인당 하나). */
interface SiteActivationConfirmationStore {
    fun insert(confirmation: SiteActivationConfirmation, requestRecordId: String)

    fun find(id: SiteActivationConfirmationId): SiteActivationConfirmation?

    fun listByActivation(activationId: PaymentMethodActivationId): List<SiteActivationConfirmation>

    fun listByAccount(accountId: MerchantAccountId): List<SiteActivationConfirmation>

    fun insertRevocation(confirmation: SiteActivationConfirmation, requestRecordId: String)
}

/** 통화 단위 기준정보와 통화 활성화 기록. */
interface CurrencyEnablementStore {
    fun findUnit(code: CurrencyCode): CurrencyUnit?

    fun find(id: CurrencyEnablementId): Versioned<CurrencyEnablement>?

    fun lock(id: CurrencyEnablementId): Versioned<CurrencyEnablement>?

    fun listByAccount(accountId: MerchantAccountId): List<CurrencyEnablement>

    fun insert(enablement: CurrencyEnablement, requestRecordId: String)

    fun update(enablement: CurrencyEnablement, expectedVersion: Long)
}

/** Site 심사 결정(append-only). */
interface SiteReviewStore {
    fun history(siteId: MerchantSiteId): List<SiteReviewDecision>

    fun append(decision: SiteReviewDecision, requestRecordId: String)
}
