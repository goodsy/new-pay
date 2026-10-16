package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.TransactionLimitPolicy
import com.paycore.master.merchant.domain.TransactionLimitPolicyId

/*
 * 승인 시작 조건 원본 저장 Port(MASTER-SNAPSHOT-002 명세 §3, §8). 공통 관례는 다른 MASTER 저장 Port와 같다(`Versioned.kt` 머리말): application이 연 MASTER 로컬 TX 안에서만,
 * 쓰기 끝 flush, append-only 표는 insert만, 잠금 읽기는 FOR UPDATE.
 */

interface TransactionLimitPolicyRepository {
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
