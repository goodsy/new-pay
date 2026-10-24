package com.paycore.simulator.merchant.domain

/** 서명된 본문에서 읽은 봉투 값(WEBHOOK-001 §4.2.0 — 가맹점은 서명되지 않은 헤더가 아니라 본문 값을 쓴다, n-7). 서명 검증을 통과한 뒤에만 믿는다. */
data class ReceivedEnvelope(val eventId: String, val type: String, val sequence: Long, val environment: String)
