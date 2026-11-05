package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.required.FinancialFactDispatchOutcome
import com.paycore.payment.payment.application.required.FinancialFactDispatcher
import com.paycore.payment.payment.application.required.FinancialFactRelayEnvelope
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * FINANCIAL 내부 Inbox HTTP 전달(FIN-FACT-RELAY-001 §4.1). **DB TX 밖에서만** 불린다(Relay가 claim TX를 커밋한 뒤). JDK HttpClient: HTTP/1.1 고정, redirect 따르지 않음,
 * 프록시 없음(시스템 속성 무시). 한 호출은 [sendBudget](연결 + 요청 상한) 안에 **반드시** 끝난다 — 응답 본문까지 포함한 전체 기한으로 기다리고 넘으면 요청을 취소한다
 * (Relay의 claim lease 판단이 이 상한에 기댄다 — 검토 M-1). 응답 본문은 [FinancialFactRelaySettings.maxAckBytes]+1바이트까지만 읽는다.
 *
 * 분류(golden `statusClassification`·`rejectCodes`):
 * - 2xx: ACK 본문을 검증(consumer = financial-batch, 보낸 eventId·effectKey와 같음, outcome 집합, DUPLICATE ⇔ originalOutcome)해 통과할 때만 [FinancialFactDispatchOutcome.Acknowledged].
 *   아니면 재시도(`ACK_UNREADABLE` — 다음 전송은 DUPLICATE로 같은 결과를 돌려받는다).
 * - 400·409·422 + 본문 `error`가 그 상태의 FINANCIAL 계약 코드(ENVELOPE_INVALID·EVENT_ID_CONFLICT·EFFECT_KEY_CONFLICT·CONTENT_INVALID)일 때만 격리(검토 m-1 —
 *   프록시·다른 서버의 4xx로 금융 사실을 격리하지 않는다). 그 밖 상태·코드·연결 실패·기한 초과는 재시도.
 * 로그·예외·결과에 본문·서명·키를 넣지 않는다(분류 코드만).
 */
@Component
class HttpFinancialFactDispatcher(private val settings: FinancialFactRelaySettings) : FinancialFactDispatcher {

    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(settings.connectTimeout)
        .build()
    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val clock: Clock = Clock.systemUTC()

    override val sendBudget: Duration get() = settings.sendBudget

    override fun dispatch(envelope: FinancialFactRelayEnvelope): FinancialFactDispatchOutcome {
        val body = FinancialFactEnvelope.body(envelope)
        val signature = FinancialFactEnvelope.signatureHeader(settings.signingKeyId, settings.signingKeyBytes, clock.instant().epochSecond, body)
        val request = HttpRequest.newBuilder(settings.endpoint)
            .timeout(settings.requestTimeout)
            .header("Content-Type", "application/json")
            .header(FinancialFactEnvelope.SIGNATURE_HEADER, signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val future = client.sendAsync(request, HttpResponse.BodyHandler<ByteArray> { BoundedBody(settings.maxAckBytes) })
        val response = try {
            future.get(sendBudget.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            return FinancialFactDispatchOutcome.Retryable("TIMEOUT")
        } catch (e: ExecutionException) {
            return FinancialFactDispatchOutcome.Retryable(
                when (e.cause) {
                    is HttpTimeoutException -> "TIMEOUT"
                    is ConnectException -> "CONNECTION"
                    else -> "IO"
                },
            )
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            return FinancialFactDispatchOutcome.Retryable("INTERRUPTED")
        }
        val status = response.statusCode()
        val bytes = response.body()
        return when {
            status in 200..299 -> acknowledgement(bytes, envelope)
            REJECT_CODES[status]?.let { codes -> errorCode(bytes) in codes } == true -> FinancialFactDispatchOutcome.Rejected("HTTP_${status}_${errorCode(bytes)}")
            else -> FinancialFactDispatchOutcome.Retryable("HTTP_$status")
        }
    }

    private fun json(bytes: ByteArray): JsonNode? {
        if (bytes.size > settings.maxAckBytes) return null
        return try {
            mapper.readTree(bytes)?.takeIf { it.isObject }
        } catch (e: JacksonException) {
            null
        }
    }

    private fun errorCode(bytes: ByteArray): String? = json(bytes)?.get("error")?.takeIf { it.isString }?.stringValue()

    private fun acknowledgement(bytes: ByteArray, sent: FinancialFactRelayEnvelope): FinancialFactDispatchOutcome {
        val node = json(bytes) ?: return UNREADABLE
        fun text(name: String): String? = node.get(name)?.takeIf { it.isString }?.stringValue()
        val consumer = text("consumer")
        val outcome = text("outcome")
        val original = node.get("originalOutcome")?.let { if (it.isString) it.stringValue() else return UNREADABLE }
        val valid = consumer == EXPECTED_CONSUMER && text("eventId") == sent.eventId && text("effectKey") == sent.effectKey &&
            ((outcome in FIRST_OUTCOMES && original == null) || (outcome == DUPLICATE && original in FIRST_OUTCOMES))
        return if (valid && outcome != null) FinancialFactDispatchOutcome.Acknowledged(EXPECTED_CONSUMER, outcome, original) else UNREADABLE
    }

    /** 응답 본문을 [max]+1바이트까지만 모은다(넘으면 구독 취소 — 큰 본문으로 메모리·시간을 쓰지 않는다). */
    private class BoundedBody(private val max: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val out = ByteArrayOutputStream()
        private val result = CompletableFuture<ByteArray>()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: List<ByteBuffer>) {
            for (buffer in item) {
                val n = minOf(buffer.remaining(), max + 1 - out.size())
                if (n > 0) ByteArray(n).also { buffer.get(it); out.write(it) }
            }
            if (out.size() > max) {
                subscription?.cancel()
                result.complete(out.toByteArray())
            }
        }

        override fun onError(throwable: Throwable) {
            result.completeExceptionally(throwable)
        }

        override fun onComplete() {
            result.complete(out.toByteArray())
        }
    }

    companion object {
        /** golden `consumer`: FINANCIAL 수신자 이름. 다른 수신자의 2xx를 ACK로 받지 않는다. */
        const val EXPECTED_CONSUMER = "financial-batch"
        const val DUPLICATE = "DUPLICATE"
        val FIRST_OUTCOMES = setOf("APPLIED", "HELD", "UNSUPPORTED")

        /** golden `rejectCodes`: 격리하는 (상태, FINANCIAL 계약 오류 코드). 같은 내용은 다시 보내도 거절된다. */
        val REJECT_CODES: Map<Int, Set<String>> = mapOf(
            400 to setOf("ENVELOPE_INVALID"),
            409 to setOf("EVENT_ID_CONFLICT", "EFFECT_KEY_CONFLICT"),
            422 to setOf("CONTENT_INVALID"),
        )
        private val UNREADABLE = FinancialFactDispatchOutcome.Retryable("ACK_UNREADABLE")
    }
}
