package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.Contract
import com.paycore.master.merchant.domain.ContractId

interface ContractRepository {
    /** 계약과 Revision 1(Scope Site 포함)을 넣고 계약 행 version을 돌려준다. */
    fun insertNew(contract: Contract): Long

    /** 잠금 없는 읽기(Revision 내용은 불변이라 Scope Merchant를 먼저 알아 잠금 순서를 지키는 데 쓴다). */
    fun find(id: ContractId): Contract?

    fun lock(id: ContractId): Versioned<Contract>?

    /** 새 Revision·승인 사실·CSA만 추가한다(기존 자식 행은 바꾸지 않는다 — append-only). 계약 행 version을 올리고 돌려준다. */
    fun update(contract: Contract, expectedVersion: Long): Long
}
