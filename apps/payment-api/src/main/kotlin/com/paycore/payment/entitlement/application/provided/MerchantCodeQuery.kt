package com.paycore.payment.entitlement.application.provided

/**
 * 가맹점 코드 조회(MERCHANT-CODE-001 §6 — D-MCODE-001 Q-MC-1·D-MCODE-002 Q-MC-8). 웹훅·결제 응답에 싣는 **출력 전용** 표시값이다 — 식별·인증·판정 입력이 아니다
 * (식별·인증은 MID + API 키). 로컬 권리 투영만 읽는다(MID 소속 → head의 v4 코드, 잠금·쓰기·외부 호출·MASTER 조회·캐시 없음).
 * 호출자 TX가 있으면 참여하고 없으면 짧은 읽기 전용 TX를 연다.
 */
interface MerchantCodeQuery {
    /**
     * [mid]가 속한 가맹점의 코드. 소속 없음·head 없음·v1~v3 head면 null(외부 필드 생략).
     * @throws EntitlementEvaluationFailure 저장소 일시 실패(코드 없음과 구별 — 호출자는 일시 불가로 처리한다).
     */
    fun merchantCodeOf(mid: String): String?
}
