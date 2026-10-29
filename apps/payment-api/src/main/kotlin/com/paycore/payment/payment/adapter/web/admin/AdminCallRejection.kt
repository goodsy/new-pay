package com.paycore.payment.payment.adapter.web.admin

/**
 * ADMIN 채널 진입 거절 분류(ADMIN-PAYMENT-OPS-001 §4.2 판정 순서 크기 → query → 헤더 문법 → 시각 창 → kid → 서명). 지표 `reason` label·WARN 로그에만 쓰고 응답은
 * 원인을 구별하지 않는다(크기 413·query 400 외에는 모두 401 `UNAUTHENTICATED`).
 */
enum class AdminCallRejection {
    /** context path가 있는 요청(기동 검사 뒤 방어 — checkpoint n-3): 서명 경로를 정할 수 없어 401 fail-closed. */
    CONTEXT_PATH_PRESENT,

    /** 본문 읽기 I/O 실패(checkpoint n-4 → S7 리뷰 L-B): 고정 503 `REQUEST_UNREADABLE`(재시도 가능 — 400·500 아님, 결정·감사 없음 — 미인증). */
    BODY_UNREADABLE,
    BODY_TOO_LARGE,
    QUERY_NOT_ALLOWED,
    MALFORMED,
    STALE,
    UNKNOWN_KID,
    SIGNATURE,
}
