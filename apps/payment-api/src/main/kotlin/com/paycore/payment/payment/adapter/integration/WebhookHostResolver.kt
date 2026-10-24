package com.paycore.payment.payment.adapter.integration

import java.net.InetAddress
import java.net.UnknownHostException

/** 이름 해석(시험이 바꿀 수 있는 adapter 내부 경계 — 외부망 없이 목적지 규칙을 시험한다). 실패는 [UnknownHostException]. 예산은 전송 adapter가 건다(m-4). */
fun interface WebhookHostResolver {
    @Throws(UnknownHostException::class)
    fun resolve(host: String): List<InetAddress>
}

/** 해석 예산 초과(m-4). 메시지에 host를 넣지 않는다(로그 비노출 — endpointRef만 기록). */
class WebhookDnsTimeoutException : UnknownHostException("name resolution exceeded its budget")

/** 해석된 주소 중 하나라도 공인이 아니다(D-WH-007, m-6). [reason]은 고정 분류. */
class WebhookDestinationBlockedException(val reason: String) : UnknownHostException("destination blocked: $reason")
