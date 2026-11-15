package com.paycore.master.merchant.domain

/**
 * 가맹점 코드 1회 변경 사유(MERCHANT-CODE-001 §3.7.2 — C-12 고정 목록, **Demo 값**: 운영 사유 목록은 정책 확정 대상). 영속 이름(`merchant_code_assignment.reason_code`·`mop1` 끝 줄)이라 바꾸지 않는다(추가만).
 */
enum class MerchantCodeChangeReason { TYPO_CORRECTION, MERCHANT_REQUEST, OTHER_POLICY }
