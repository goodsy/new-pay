package com.paycore.master.merchant.domain

/**
 * 접수 건 전이 입력(MERCHANT-FLOW-001 §4B.6 MINOR-1 — 이름 [구현 관례]): '사유 및 비고'([reason] — 되돌리기·계약 불가 필수, 그 밖 선택), 계약서 수령 근거([document] —
 * 계약 완료만 필수, 그 밖 전이에 오면 거절), 계약 완료면 [completion](버전 조건 + 잠금 뒤 읽은 최신 사업자 계약). [ContractIntake.transitionTo]가 Domain에서 고정 code로 검사한다.
 */
data class ContractIntakeTransitionInput(
    val reason: BusinessChangeReason? = null,
    val document: ContractDocumentRef? = null,
    val completion: ContractIntakeCompletion? = null,
) {
    companion object {
        /** 입력 없음(계약서 접수 전이 — 사유 선택을 비운 경우). */
        val NONE = ContractIntakeTransitionInput()
    }
}

/**
 * 계약 완료 전이의 추가 입력(§4B.6): 운영자가 넣은 버전 조건 [terms]와 Application이 사업자 행 잠금 뒤 같은 TX에서 읽은 그 사업자의 최신 사업자 계약 [latestContract]
 * (`BusinessContractRepository.latest` — 없으면 null). 접수 건 종류가 신규면 새 계약, 변경이면 [latestContract]에 새 버전을 만든다.
 */
data class ContractIntakeCompletion(val terms: BusinessContractTerms, val latestContract: BusinessContract?)
