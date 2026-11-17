package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.MerchantAccountId

/**
 * 가맹점 코드 Port(MERCHANT-CODE-001 §6 — 출력 전용 표시값). 웹훅 봉투·결제 생성/조회 응답에 `merchantCode`를 싣기 위해서만 쓴다 —
 * 결제 판정·금액·상태·멱등 키·주문 키에 쓰지 않는다(Financial Consistency). 코드 없는 MID는 null(외부 필드 생략).
 * 운영 구현은 entitlement 모듈의 공개 조회(adapter.integration `EntitlementMerchantCodeAdapter`).
 */
fun interface MerchantCodePort {
    /** @throws PersistenceFailure 저장소 일시 실패(코드 없음과 구별). */
    fun merchantCodeOf(merchantAccountId: MerchantAccountId): String?
}
