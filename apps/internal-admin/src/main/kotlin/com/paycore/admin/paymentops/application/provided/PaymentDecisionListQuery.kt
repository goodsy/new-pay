package com.paycore.admin.paymentops.application.provided

/**
 * 대사 전용 PAYMENT 결정 목록 조회 Port(ADMIN-PAYMENT-OPS-001 §4.17, S7 리뷰 L-2 — 대사는 명령을 보낼 수 없게 [PaymentOpsClient]와 분리). 서명된 GET만, DB TX 밖에서만.
 */
interface PaymentDecisionListQuery {
    /**
     * 결정 목록(§4.17 `GET /internal/v1/admin/operator-decisions/after/{watermark}` — `seq > watermark` 오름차순 최대 200행, execution pool).
     * [DecisionPage.Page.nextWatermark]는 PAYMENT가 DB 시각으로 정한 **안정 watermark**(커밋 지연 행을 건너뛰지 않게 — 그 행은 전체 스캔이 잡는다).
     */
    fun decisionsAfter(watermark: Long): DecisionPage

    /**
     * PAYMENT가 **결정 행 없이** 400으로 끝내는 code 집합(실행 POST 응답 분류기의 400 경로와 같은 집합 — golden ③ 400 code ∪ golden ② 400 사유. 본문 읽기 실패는 503 — I-124).
     * 이 code로 OWNER_REJECTED가 된 승인은 PAYMENT 결정이 없는 것이 정상이라 `MISSING_DECISION`이 아니다(S7 리뷰 M-1(c)).
     */
    val decisionlessRejectionCodes: Set<String>
}
