package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PaymentMethodActivation
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.ActivationTransition

/**
 * 개통(D-MD-017, D-MS-003). 현재 상태는 `payment_method_activation`(UPDATE, `@Version`), 전이 이력은 append-only `payment_method_activation_event`.
 * 쓰기 메서드는 상태 변경과 이력 한 건을 같은 TX에 넣는다([requestRecordId] = 관리 명령 요청 기록 ID).
 */
interface PaymentMethodActivationRepository {
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
