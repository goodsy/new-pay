package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.MerchantId

interface MerchantRepository {
    fun insert(merchant: Merchant)

    fun lock(id: MerchantId): Versioned<Merchant>?

    /** 잠금 없는 읽기(소속 Business 같은 불변 값을 먼저 알아 잠금 순서를 지키는 데 쓴다). */
    fun find(id: MerchantId): Merchant?

    /**
     * MERCHANT-CODE-001 §3.3·§3.7.4: 그 코드가 배정된 적 있는 가맹점(현재·은퇴 모두 — 배정 표 UNIQUE 색인 한 행). 없으면 null.
     * 명령 TX 안 사전 조회(등록 `MERCHANT_CODE_TAKEN` 판정 입력)라 CQRS Reader가 아니라 Aggregate 저장 Port에 둔다.
     */
    fun findCodeOwner(code: MerchantCode): MerchantId?

    /** entitlement revision 반영. 기대 version이 다르면 [PersistenceFailure.OptimisticConflict]. 새 version을 돌려준다. */
    fun update(merchant: Merchant, expectedVersion: Long): Long
}
