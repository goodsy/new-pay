package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.application.required.DispatchOutcome
import com.paycore.master.merchant.application.required.EntitlementDispatcher
import com.paycore.master.merchant.application.required.RelayEnvelope
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets

/**
 * PAYMENT 내부 Inbox HTTP 전달(push relay, 명세 §7 결정). **DB TX 밖에서만** 불린다(Relay가 claim TX를 커밋한 뒤). 응답 2xx만 성공(PAYMENT가
 * 내구 커밋한 뒤 응답 — ACK), 5xx·timeout·연결 오류는 재시도, 4xx는 계약 오류(격리)로 분류한다. 단순 HTTP 성공을 전달 완료로 보지 않고
 * 응답 본문의 outcome·appliedRevision을 ACK로 남긴다(본문을 읽지 못하면 재시도 — 다음 전달은 DUPLICATE로 같은 결과를 돌려받는다).
 * 로그·예외에 본문·서명 키를 넣지 않는다.
 */
@Component
class HttpEntitlementDispatcher(private val settings: EntitlementSettings) : EntitlementDispatcher {

    private val client: RestClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(settings.dispatch.connectTimeout)
                setReadTimeout(settings.dispatch.readTimeout)
            },
        )
        .build()

    private val mapper: JsonMapper = JsonMapper.builder().build()

    override fun dispatch(envelope: RelayEnvelope): DispatchOutcome {
        val body = EntitlementEnvelope.body(envelope)
        val signature = EntitlementEnvelope.signatureHeader(settings.dispatch.signingKeyId, settings.dispatch.signingKeyBytes, body)
        return try {
            client.post()
                .uri(settings.dispatch.endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header(EntitlementEnvelope.SIGNATURE_HEADER, signature)
                .body(body)
                .exchange { _, response ->
                    val status = response.statusCode
                    val bytes = response.body.use { it.readAllBytes() }
                    when {
                        status.is2xxSuccessful -> acknowledgement(bytes)
                        status.is4xxClientError -> DispatchOutcome.Rejected("HTTP_${status.value()}")
                        else -> DispatchOutcome.Retryable("HTTP_${status.value()}")
                    }
                }
        } catch (e: ResourceAccessException) {
            DispatchOutcome.Retryable(if (e.cause is java.net.SocketTimeoutException) "TIMEOUT" else "CONNECTION")
        }
    }

    private fun acknowledgement(bytes: ByteArray): DispatchOutcome = try {
        val node = mapper.readTree(String(bytes, StandardCharsets.UTF_8))
        val outcome = node.get("outcome")?.stringValue()
        val applied = node.get("appliedRevision")
        val consumer = node.get("consumer")?.stringValue()
        val original = node.get("originalOutcome")?.stringValue()
        if (outcome == null || applied == null || !applied.isIntegralNumber || consumer == null || outcome !in OUTCOMES ||
            (original != null && original !in OUTCOMES)
        ) {
            DispatchOutcome.Retryable("ACK_UNREADABLE")
        } else {
            DispatchOutcome.Acknowledged(consumer, outcome, applied.longValue(), original)
        }
    } catch (e: tools.jackson.core.JacksonException) {
        DispatchOutcome.Retryable("ACK_UNREADABLE")
    }

    private companion object {
        val OUTCOMES = setOf("APPLIED", "LEASE_EXTENDED", "STALE", "DUPLICATE", "INTEGRITY_CONFLICT", "UNSUPPORTED")
    }
}
