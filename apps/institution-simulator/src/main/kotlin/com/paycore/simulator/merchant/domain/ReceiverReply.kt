package com.paycore.simulator.merchant.domain

import java.time.Duration

/** 수신기 응답: HTTP 상태, 응답 전 지연(저장 TX가 끝난 뒤), 응답 본문 크기(바이트). 2xx만 "받음"이다(WEBHOOK-001 WH-09). */
data class ReceiverReply(val status: Int, val delay: Duration, val bodyBytes: Int) {
    val accepted: Boolean get() = status in 200..299
}
