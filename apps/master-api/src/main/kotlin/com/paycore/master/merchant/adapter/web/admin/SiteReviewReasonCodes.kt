package com.paycore.master.merchant.adapter.web.admin

/**
 * Site 심사 사유 코드(MERCHANT-OPS-002 C-37 — ADMIN 고정 목록과 같은 값을 복제, C-10). Domain `DecisionReason`은 자유 문구를 허용하지만 관리 채널은 코드만 받는다(자유 문구 불가 — B1·B2와 같은 규칙).
 * **Demo 값 — 운영 사유 체계 아님**(LIVE Gate). 승인 사유와 거절 사유는 겹치지 않는다(승인 본문에 거절 코드·거절 본문에 승인 코드 = 400).
 */
object SiteReviewReasonCodes {
    val APPROVE: Set<String> = setOf("REVIEW_PASSED")
    val REJECT: Set<String> = setOf("DOCUMENT_INSUFFICIENT", "PROHIBITED_BUSINESS", "SITE_UNREACHABLE", "OTHER_POLICY")
}
