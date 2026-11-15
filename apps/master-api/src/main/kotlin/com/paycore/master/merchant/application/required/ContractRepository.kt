package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.Contract
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.MerchantId

interface ContractRepository {
    /** 계약과 Revision 1(Scope Site 포함)을 넣고 계약 행 version을 돌려준다. */
    fun insertNew(contract: Contract): Long

    /** 잠금 없는 읽기(Revision 내용은 불변이라 Scope Merchant를 먼저 알아 잠금 순서를 지키는 데 쓴다). */
    fun find(id: ContractId): Contract?

    fun lock(id: ContractId): Versioned<Contract>?

    /** 새 Revision·승인 사실·CSA만 추가한다(기존 자식 행은 바꾸지 않는다 — append-only). 계약 행 version을 올리고 돌려준다. */
    fun update(contract: Contract, expectedVersion: Long): Long

    /**
     * MERCHANT-CODE-001 §3.7.1·§3.7.4(C-14): 그 가맹점을 대상으로 하는 계약(`contract.merchant_id`)의 어느 Revision에든 승인 사실 행이 있는가("첫 계약 승인 뒤").
     * 코드 변경 명령 TX 안에서 **merchant 잠금 뒤** 부른다(READ COMMITTED — 그 시점 최신 커밋, 영속성 컨텍스트 캐시 미사용). 명령 판정 입력이라 CQRS Reader가 아니다.
     */
    fun hasApprovedRevisionFor(merchantId: MerchantId): Boolean
}
