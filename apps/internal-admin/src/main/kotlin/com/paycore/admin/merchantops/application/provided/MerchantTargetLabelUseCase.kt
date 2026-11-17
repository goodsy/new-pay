package com.paycore.admin.merchantops.application.provided

/**
 * 승인함·내 요청·감사 화면의 가맹점 대상 표시 라벨(MERCHANT-CODE-001 §5.6.3 MC-3b, C-29·C-35). 조회자는 요청마다 계정 현재 행(ACTIVE + VIEW 4역할 VIEWER·OPERATOR·APPROVER·
 * SECURITY_ADMIN — 승인함·감사 화면을 볼 수 있는 역할 집합)으로 판정한다. 판정 순서: 역할(아니면 Forbidden — MASTER 미호출) → 입력(1..50·`mer_` 형식·중복 금지, 아니면
 * InvalidInput — MASTER 미호출, 창 소비 없음) → 라벨 전용 actor별 분당 창 1 소비(가맹점 검색 창 미사용, 초과 RateLimited — MASTER 미호출) → MASTER 라벨 묶음(DB TX 밖).
 * **감사 없음**(D-MOPS-002 — 목록 수준 표시값). 캐시 없음.
 */
interface MerchantTargetLabelUseCase {
    /** [merchantIds]가 null이면 본문 엄격 해석 실패(역할 판정 뒤 InvalidInput — 본문 원문은 받지 않는다). */
    fun labels(viewerActorId: String, merchantIds: List<String>?): MerchantTargetLabelResult
}
