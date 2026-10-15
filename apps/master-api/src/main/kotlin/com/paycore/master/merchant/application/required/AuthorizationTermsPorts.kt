package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.ActivationTransition
import com.paycore.master.merchant.domain.CardRouteActivation
import com.paycore.master.merchant.domain.CardRouteActivationId
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.PolicyAssignment
import com.paycore.master.merchant.domain.PolicyAssignmentId
import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.PolicyKind
import com.paycore.master.merchant.domain.PolicyVersion
import com.paycore.master.merchant.domain.PolicyVersionId
import com.paycore.master.merchant.domain.TransactionLimitPolicy
import com.paycore.master.merchant.domain.TransactionLimitPolicyId

/*
 * 승인 시작 조건 원본 저장 Port(MASTER-SNAPSHOT-002 명세 §3, §8). 공통 관례는 MasterStores와 같다: application이 연 MASTER 로컬 TX 안에서만,
 * 쓰기 끝 flush, append-only 표는 insert만, 잠금 읽기는 FOR UPDATE.
 */

interface TransactionLimitPolicyStore {
    fun find(id: TransactionLimitPolicyId): Versioned<TransactionLimitPolicy>?

    fun lock(id: TransactionLimitPolicyId): Versioned<TransactionLimitPolicy>?

    /** 그 Merchant의 정책 전체(CSA 기본·MID Override, 지난 것 포함). 중첩 판정과 Snapshot 조립에 쓴다. */
    fun listByMerchant(merchantId: MerchantId): List<TransactionLimitPolicy>

    fun insert(policy: TransactionLimitPolicy, requestRecordId: String)

    /** 종료(effective_to·ended_*)만 바뀐다. */
    fun update(policy: TransactionLimitPolicy, expectedVersion: Long)

    /** CSA가 속한 계약(불변 소속). 없으면 null. */
    fun contractOfAgreement(agreementId: ContractServiceAgreementId): ContractId?
}

interface PolicyVersionStore {
    fun find(id: PolicyVersionId): PolicyVersion?

    fun findAll(ids: Collection<PolicyVersionId>): List<PolicyVersion>

    fun listByCode(kind: PolicyKind, code: PolicyCode): List<PolicyVersion>

    fun insert(version: PolicyVersion, requestRecordId: String)
}

interface PolicyAssignmentStore {
    fun find(id: PolicyAssignmentId): Versioned<PolicyAssignment>?

    fun lock(id: PolicyAssignmentId): Versioned<PolicyAssignment>?

    fun listByAccount(accountId: MerchantAccountId): List<PolicyAssignment>

    fun insert(assignment: PolicyAssignment, requestRecordId: String)

    fun update(assignment: PolicyAssignment, expectedVersion: Long)
}

/** 승인 시작 조건 누락 점검 조회(jOOQ adapter.query, 읽기 전용). */
interface AuthorizationTermsCoverageReader {
    fun overview(now: java.time.Instant): com.paycore.master.merchant.application.provided.TermsCoverageOverview
}

interface CardRouteActivationStore {
    fun find(id: CardRouteActivationId): Versioned<CardRouteActivation>?

    fun lock(id: CardRouteActivationId): Versioned<CardRouteActivation>?

    fun listByAccount(accountId: MerchantAccountId): List<CardRouteActivation>

    /** 그 개통 전이 이력(append-only)에 남은 근거 참조 전체(재개 근거 "새 것" 판정). */
    fun evidenceHistory(id: CardRouteActivationId): List<EvidenceRef>

    fun insert(route: CardRouteActivation, transition: ActivationTransition, requestRecordId: String)

    fun update(route: CardRouteActivation, expectedVersion: Long, transition: ActivationTransition, requestRecordId: String)
}
