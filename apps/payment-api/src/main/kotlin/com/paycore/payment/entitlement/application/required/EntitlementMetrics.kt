package com.paycore.payment.entitlement.application.required

import com.paycore.payment.entitlement.domain.InboxOutcome

/** PAYMENT 권리 지표(명세 §11). label은 분류값만(outcome·결과 종류·reason), MID·merchantId 금지. */
interface EntitlementMetrics {
    fun inboxOutcome(outcome: InboxOutcome)

    fun inboxRejected(kind: String)

    fun eligibility(result: String, reason: String?)

    /** 승인 시작 허용 판정 결과(MASTER-SNAPSHOT-002). label은 결과·reason 분류만. */
    fun authorizationTerms(result: String, reason: String?)

    /**
     * MERCHANT-CODE-001 §6 경보 `merchant_code_changed_with_mid`: 적용한 Snapshot이 이미 코드가 있던 가맹점의 코드를 다른 코드로 바꾸거나 없앴다(MID 있는 가맹점 — 정상 경로에서 불가능,
     * 소실도 같은 카운터 — 리뷰 MINOR-1).
     * label 없음(코드 값·merchantId는 넣지 않는다 — 카디널리티·비노출).
     */
    fun merchantCodeChangedWithMid()
}
