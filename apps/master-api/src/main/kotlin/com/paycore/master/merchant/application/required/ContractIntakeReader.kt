package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.ContractIntakeSectionView
import com.paycore.master.merchant.domain.BusinessId

/**
 * 사업자 상세 '계약 진행' 섹션 읽기 Port(MERCHANT-FLOW-001 FLOW-1 — jOOQ `adapter.query`, 한 읽기 전용 REPEATABLE READ TX). 사업자가 없으면 null(접수 건 없음 = 빈 목록).
 * 판정용이 아니다 — 생성 조건·가맹점 등록 관문은 사업자 행 잠금 뒤 JPA `ContractIntakeRepository`로 읽는다(§4A.5 m-3).
 */
interface ContractIntakeReader {
    fun section(businessId: BusinessId): ContractIntakeSectionView?
}
