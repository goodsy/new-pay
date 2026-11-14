package com.paycore.master.merchant.domain

/**
 * 가맹점 코드 배정 경로(MERCHANT-CODE-001 §3.2 — V13 `source` CHECK와 같은 집합, 영속 이름). REGISTRATION = B1 가맹점 등록, BACKFILL = V13 기존 행 채우기(L-2 — 이력 이벤트 없음),
 * CHANGE = 유일한 1회 변경(seq 2, MC-1b — 이 배치에는 그 경로가 없다).
 */
enum class MerchantCodeSource { REGISTRATION, BACKFILL, CHANGE }
