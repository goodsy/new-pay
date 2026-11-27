package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.MerchantRegistrationBasis

/**
 * 가맹점 등록 근거 기록 저장 Port(MERCHANT-FLOW-001 §4B.2·§4B.8 — BR-BIZ-337, ARCHITECTURE-CONVENTIONS §14 ③ 기록 `…Store`). **INSERT 전용**: 고치거나 지우는 메서드가 없다.
 * 가맹점 등록 명령의 같은 MASTER 로컬 TX(사업자 행 잠금 뒤)에서 가맹점 INSERT 다음에 부른다. 읽기 메서드도 두지 않는다 — 판정에 쓰지 않는다(화면 표시는 jOOQ Reader).
 */
interface MerchantRegistrationBasisStore {
    /** 1행 INSERT(PK = 가맹점 — 가맹점당 1행). 위반은 결함. */
    fun insert(basis: MerchantRegistrationBasis)
}
