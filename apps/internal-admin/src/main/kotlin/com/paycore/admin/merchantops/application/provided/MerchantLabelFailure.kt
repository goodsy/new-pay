package com.paycore.admin.merchantops.application.provided

/**
 * 라벨을 얻지 못한 분류(TXN-LIST-ENRICH-001 §8 지표 `result`): [BUSY] ADMIN 라벨 permit 없음 또는 MASTER 503 `ADMIN_READ_BUSY`, [UNREADABLE] 200인데 모양·대응 위반(전부 아니면 전무),
 * [UNAVAILABLE] 그 밖(timeout·연결 실패·401·400·404·5xx·응답 상한 64 KiB 초과 — §8, 리뷰 M-a: 상한 초과는 본문을 끝까지 읽지 않아 모양을 판정하지 않으므로 UNREADABLE이 아니다).
 */
enum class MerchantLabelFailure { BUSY, UNREADABLE, UNAVAILABLE }
