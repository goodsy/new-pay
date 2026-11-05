package com.paycore.financial.paymentfact.adapter.web

import com.paycore.financial.paymentfact.application.provided.ReceivePaymentFactFailure
import com.paycore.financial.paymentfact.application.provided.ReceivePaymentFactResult
import com.paycore.financial.paymentfact.application.provided.ReceivePaymentFactUseCase
import com.paycore.financial.paymentfact.domain.PaymentFactContractViolation
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * PAYMENT → FINANCIAL 결제 사실 내부 Inbox(push relay 수신 — FIN-FACT-RELAY-001 §4.1, D-ACQ-001). 진입 필터([PaymentFactInboxEntryFilter])가 이 경로 밖·크기 초과를 먼저 거른다.
 *
 * 순서(TX 밖 검증 → Use Case의 로컬 TX → 커밋 뒤 응답):
 * 1. 서명(문법·시각 창·kid·HMAC) 실패 → 401 `UNAUTHENTICATED`(사유 비구별, 저장 없음)
 * 2. 봉투 해석 불가 → 400 `ENVELOPE_INVALID`, 지원 사실의 내용 계약 위반 → 422 `CONTENT_INVALID`(저장 없음 — PAYMENT는 격리)
 * 3. Use Case → 200 `{consumer, eventId, effectKey, outcome[, originalOutcome]}`(내구 커밋 뒤 ACK) / 같은 eventId·다른 본문 409 `EVENT_ID_CONFLICT` /
 *    다른 eventId·같은 effect key 409 `EFFECT_KEY_CONFLICT` / 일시 실패·결과 불명확 503 `TEMPORARILY_UNAVAILABLE`(PAYMENT가 같은 eventId로 다시 보낸다)
 * 응답·로그에 본문·서명·키를 넣지 않는다. 결과는 counter `paycore.financial.payment-fact.inbox{result}`(label에 ID 없음).
 */
@RestController
class PaymentFactInboxController(
    private val receive: ReceivePaymentFactUseCase,
    private val settings: PaymentFactInboxSettings,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(PaymentFactInboxController::class.java)

    @PostMapping(PaymentFactSignature.PATH, consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun receive(
        @RequestBody body: ByteArray,
        @RequestHeader(name = PaymentFactSignature.HEADER, required = false) signature: String?,
    ): ResponseEntity<Map<String, Any>> {
        val verification = PaymentFactSignature.verify(signature, body, Instant.now().epochSecond, settings.clockWindow, settings::key)
        if (verification is PaymentFactSignature.Verification.Rejected) {
            log.warn("payment fact rejected status=401 reason={}", verification.reason)
            return reject(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "unauthenticated")
        }
        val keyId = (verification as PaymentFactSignature.Verification.Accepted).keyId
        val envelope = try {
            PaymentFactEnvelopeParser.parse(body, keyId)
        } catch (e: PaymentFactEnvelopeParser.EnvelopeInvalid) {
            return reject(HttpStatus.BAD_REQUEST, "ENVELOPE_INVALID", "envelope_invalid")
        } catch (e: PaymentFactContractViolation) {
            return reject(HttpStatus.UNPROCESSABLE_CONTENT, "CONTENT_INVALID", "content_invalid")
        }
        return try {
            when (val result = receive.receive(envelope)) {
                is ReceivePaymentFactResult.Stored -> {
                    count(if (result.duplicate) "duplicate" else result.outcome.name.lowercase())
                    val ack = linkedMapOf<String, Any>(
                        "consumer" to CONSUMER, "eventId" to envelope.eventId, "effectKey" to envelope.effectKey,
                        "outcome" to if (result.duplicate) DUPLICATE else result.outcome.name,
                    )
                    if (result.duplicate) ack["originalOutcome"] = result.outcome.name
                    ResponseEntity.ok(ack)
                }
                ReceivePaymentFactResult.EventIdConflict -> reject(HttpStatus.CONFLICT, "EVENT_ID_CONFLICT", "event_id_conflict")
                ReceivePaymentFactResult.EffectKeyConflict -> reject(HttpStatus.CONFLICT, "EFFECT_KEY_CONFLICT", "effect_key_conflict")
            }
        } catch (e: ReceivePaymentFactFailure) {
            // 예외 메시지 원문 대신 분류(예외 클래스 이름 사슬)만 남긴다.
            log.warn(
                "operation=ReceivePaymentFact eventId={} result=TEMPORARILY_UNAVAILABLE failure={}",
                envelope.eventId, generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSE_DEPTH).joinToString(" <- ") { it.javaClass.simpleName },
            )
            count("unavailable")
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to "TEMPORARILY_UNAVAILABLE"))
        }
    }

    private fun reject(status: HttpStatus, error: String, result: String): ResponseEntity<Map<String, Any>> {
        count(result)
        if (status != HttpStatus.UNAUTHORIZED) log.warn("payment fact rejected status={} error={}", status.value(), error)
        return ResponseEntity.status(status).body(mapOf("error" to error))
    }

    private fun count(result: String) = registry.counter(METRIC, "result", result).increment()

    companion object {
        /** 이 수신자의 이름(ACK consumer). */
        const val CONSUMER = "financial-batch"
        const val DUPLICATE = "DUPLICATE"
        const val METRIC = "paycore.financial.payment-fact.inbox"
        private const val MAX_CAUSE_DEPTH = 10
    }
}
