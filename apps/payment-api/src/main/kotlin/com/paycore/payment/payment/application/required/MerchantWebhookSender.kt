package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.Environment

/** 전송 요청 하나. [environment]는 전달 행의 환경 — 목적지 예외(TEST allowlist)는 TEST일 때만 적용된다(m-6). toString에 URL·본문을 넣지 않는다. */
class MerchantWebhookSendRequest(val environment: Environment, val url: String, val headers: Map<String, String>, val body: ByteArray) {
    override fun toString(): String = "MerchantWebhookSendRequest(environment=$environment, bodyBytes=${body.size})"
}

/** 전송 결과: 시간 안의 2xx만 성공(WH-09). 실패 코드는 고정 분류(HTTP_xxx·REDIRECT·TIMEOUT·DNS_TIMEOUT·DNS_FAILURE·CONNECTION·TLS·DESTINATION_REJECTED·DESTINATION_BLOCKED). */
sealed interface MerchantWebhookSendResult {
    data class Succeeded(val httpStatus: Int) : MerchantWebhookSendResult

    data class Failed(val code: String, val httpStatus: Int?) : MerchantWebhookSendResult
}

/** 가맹점 HTTP 전송 Port(DB TX 밖에서만 부른다). 구현은 목적지 검사·연결 고정·redirect 금지·기한·응답 상한을 지킨다(D-WH-007). */
interface MerchantWebhookSender {
    fun send(request: MerchantWebhookSendRequest): MerchantWebhookSendResult
}
