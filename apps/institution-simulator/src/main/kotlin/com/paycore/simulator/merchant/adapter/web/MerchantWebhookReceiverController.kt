package com.paycore.simulator.merchant.adapter.web

import com.paycore.simulator.authentication.application.provided.SimulatorClientAuthenticator
import com.paycore.simulator.merchant.application.provided.MerchantWebhookReceiptQuery
import com.paycore.simulator.merchant.application.provided.ReceiveMerchantWebhookCommand
import com.paycore.simulator.merchant.application.provided.ReceiveMerchantWebhookResult
import com.paycore.simulator.merchant.application.provided.ReceiveMerchantWebhookUseCase
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 가상 가맹점 수신기 HTTP(WEBHOOK-001 §9 — Demo 대역): `POST /sim/merchant/v1/webhooks/{receiverId}`(PG가 보내는 Webhook — 서명 검증·원장·시나리오 응답)와
 * `GET …/{receiverId}/receipts`(E2E 확인용 — 소유 PG client Bearer, 다른 client·모르는 수신기는 404). 응답에 입력값을 echo하지 않는다. 지연 시나리오는 저장 TX가 끝난 뒤
 * 응답 전에만 기다린다.
 */
@RestController
class MerchantWebhookReceiverController(
    private val receive: ReceiveMerchantWebhookUseCase,
    private val receipts: MerchantWebhookReceiptQuery,
    private val clients: SimulatorClientAuthenticator,
) {

    @PostMapping("/sim/merchant/v1/webhooks/{receiverId}")
    fun receive(
        @PathVariable receiverId: String,
        @RequestHeader(SIGNATURE_HEADER, required = false) signature: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<String> {
        val bytes = body ?: ByteArray(0)
        if (bytes.size > MerchantWebhookJson.MAX_BODY_BYTES) return json(HttpStatus.CONTENT_TOO_LARGE, "{\"code\":\"BODY_TOO_LARGE\"}")
        return when (val result = receive.receive(ReceiveMerchantWebhookCommand(receiverId, signature, bytes, MerchantWebhookJson.envelope(bytes)))) {
            ReceiveMerchantWebhookResult.UnknownReceiver -> json(HttpStatus.NOT_FOUND, "{\"code\":\"NOT_FOUND\"}")
            is ReceiveMerchantWebhookResult.Answered -> {
                val reply = result.reply
                if (!reply.delay.isZero) Thread.sleep(reply.delay.toMillis())
                val builder = ResponseEntity.status(reply.status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                when {
                    reply.status == HttpStatus.FOUND.value() -> builder.header(HttpHeaders.LOCATION, REDIRECT_TARGET).build()
                    reply.bodyBytes > 0 -> builder.contentType(MediaType.TEXT_PLAIN).body("x".repeat(reply.bodyBytes))
                    else -> builder.contentType(MediaType.APPLICATION_JSON).body("{\"outcome\":\"${result.outcome.name}\"}")
                }
            }
        }
    }

    @GetMapping("/sim/merchant/v1/webhooks/{receiverId}/receipts")
    fun receipts(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?, @PathVariable receiverId: String): ResponseEntity<String> {
        val clientId = authorization?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER)?.let { clients.authenticate(it) }
            ?: return json(HttpStatus.UNAUTHORIZED, "{\"code\":\"UNAUTHORIZED\"}")
        val list = receipts.receipts(clientId, receiverId) ?: return json(HttpStatus.NOT_FOUND, "{\"code\":\"NOT_FOUND\"}")
        return json(HttpStatus.OK, MerchantWebhookJson.receipts(list))
    }

    private fun json(status: HttpStatus, body: String): ResponseEntity<String> =
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body)

    private companion object {
        const val SIGNATURE_HEADER = "PayCore-Signature"
        const val BEARER = "Bearer "
        const val REDIRECT_TARGET = "https://merchant.example.test/moved"
    }
}
