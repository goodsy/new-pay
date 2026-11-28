package com.paycore.master.merchant.domain

/**
 * 사업자의 '계약·가맹점 이력'(BIZ-CRIT-APPROVAL-001 머리말 용어 — BR-BIZ-386, D-GOV-026 ②): 사업자 계약이 상태와 관계없이(효력 전·유효·정지·해지·만료) 한 번이라도 있었는지
 * ([contractEver]), 가맹점이 종료 포함 한 번이라도 있었는지([merchantEver]). **계약 접수 건은 들어가지 않는다**(접수만 있으면 이력 없음). BR-BIZ-325의 '사업자 계약 있음'과 다른 기준이다.
 * '이력 없음' 상수를 두지 않는다(MINOR-4 — 운영 코드가 판정 없이 기본값을 넘기지 못하게). Application이 **사업자 행 잠금 뒤** 행 존재로 판정해 넘긴다(§3.6 — 제안 TX·실행 TX 두 번). 계약·가맹점은 물리 삭제가 없어 이 값은 false → true 방향으로만 바뀐다(§1.1 판정이 약해지지 않음).
 */
data class BusinessContractMerchantHistory(val contractEver: Boolean, val merchantEver: Boolean) {
    /** 계약·가맹점 이력 있음(§1.1 열 '있음'). */
    val exists: Boolean get() = contractEver || merchantEver
}
