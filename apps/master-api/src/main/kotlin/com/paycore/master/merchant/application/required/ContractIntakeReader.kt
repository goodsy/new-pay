package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.ContractIntakeSectionView
import com.paycore.master.merchant.domain.BusinessContract
import com.paycore.master.merchant.domain.BusinessId

/**
 * 사업자 상세 '계약 진행' 섹션 읽기 Port(MERCHANT-FLOW-001 FLOW-1 — jOOQ `adapter.query`, 한 읽기 전용 REPEATABLE READ TX). 사업자가 없으면 null(접수 건 없음 = 빈 목록).
 * 판정용이 아니다 — 생성 조건·가맹점 등록 관문은 사업자 행 잠금 뒤 JPA `ContractIntakeRepository`로 읽는다(§4A.5 m-3).
 */
interface ContractIntakeReader {
    /** 접수 건·전이 이력 + 최신 사업자 계약(복원된 Domain — 표시 계산용) + 연결 가맹점 수(종료 제외 — BR-BIZ-312)를 **한 스냅샷**에서 읽는다. */
    fun section(businessId: BusinessId): ContractIntakeSection?
}

/** [ContractIntakeReader.section] 결과. [view]의 사업자 계약 칸은 비어 있다 — Query Service가 [contract]·판정 시각으로 채운다. */
data class ContractIntakeSection(val view: ContractIntakeSectionView, val contract: BusinessContract?, val linkedMerchantCount: Int)
