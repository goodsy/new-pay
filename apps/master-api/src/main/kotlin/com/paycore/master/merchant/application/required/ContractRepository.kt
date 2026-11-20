package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.Contract
import com.paycore.master.merchant.domain.ContractCode
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.MerchantId
import java.time.Instant

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

    /**
     * SITE-CONTRACT-CODE-001 §3.4(C-12·C-13 — 등록 경로 전용, [lock]·[find]·[update]는 코드 표를 읽거나 쓰지 않는다): 그 가맹점(`contract.merchant_id`)의 다음 계약 코드 순번
     * = `MAX(code_seq) + 1`(없으면 1). **Scope merchant 행 잠금 뒤**, `beginWrites()` **전**에 부른다(READ COMMITTED 최신 커밋).
     */
    fun nextContractCodeSeq(merchantId: MerchantId): Int

    /** 코드 행 INSERT(append-only, source REGISTRATION). 계약 행([insertNew])이 먼저 flush된 뒤 같은 TX에서 부른다(FK `fk_cc_contract`). 1062는 결함 분류(C-3). */
    fun insertCode(contractId: ContractId, merchantId: MerchantId, code: ContractCode, assignedAt: Instant, assignedBy: ActorRef)

    /** 명령 응답·재생용 코드 조회(저장 reference → 코드, C-4). 없으면 null(호출자가 결함으로 본다). */
    fun findCode(contractId: ContractId): ContractCode?
}
