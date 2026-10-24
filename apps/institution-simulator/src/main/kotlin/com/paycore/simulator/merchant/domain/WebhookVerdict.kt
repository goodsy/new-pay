package com.paycore.simulator.merchant.domain

/** 서명 헤더 판정(WEBHOOK-001 §21.0 — golden `webhook-signature-v1`의 expected 값과 같은 이름). 판정 순서: 문법 → 재전송 창 → 서명. */
enum class WebhookVerdict {
    ACCEPTED,
    MALFORMED,
    STALE,
    SIGNATURE,
}
