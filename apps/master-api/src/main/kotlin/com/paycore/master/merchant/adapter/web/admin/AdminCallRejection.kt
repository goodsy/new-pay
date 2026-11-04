package com.paycore.master.merchant.adapter.web.admin

/**
 * ADMIN → MASTER 채널 진입 거절 분류(MERCHANT-OPS-001 §3 — ADMIN-PAYMENT-OPS-001 §4.2와 같은 판정 순서: 크기 → query → 헤더 문법 → 시각 창 → kid → 서명).
 * 지표 `reason` label·WARN 로그에만 쓰고 응답은 원인을 구별하지 않는다(크기 413·query 400·본문 읽기 실패 503 외에는 모두 401 `UNAUTHENTICATED`).
 */
enum class AdminCallRejection {
    CONTEXT_PATH_PRESENT,
    BODY_UNREADABLE,
    BODY_TOO_LARGE,
    QUERY_NOT_ALLOWED,
    MALFORMED,
    STALE,
    UNKNOWN_KID,
    SIGNATURE,
}
