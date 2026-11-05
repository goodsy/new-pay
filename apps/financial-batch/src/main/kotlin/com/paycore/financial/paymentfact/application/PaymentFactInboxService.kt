package com.paycore.financial.paymentfact.application

import com.paycore.financial.paymentfact.application.provided.InboundPaymentFact
import com.paycore.financial.paymentfact.application.provided.ReceivePaymentFactFailure
import com.paycore.financial.paymentfact.application.provided.ReceivePaymentFactResult
import com.paycore.financial.paymentfact.application.provided.ReceivePaymentFactUseCase
import com.paycore.financial.paymentfact.application.required.PaymentFactClock
import com.paycore.financial.paymentfact.application.required.PaymentFactInboxRecord
import com.paycore.financial.paymentfact.application.required.PaymentFactInboxStore
import com.paycore.financial.paymentfact.application.required.PaymentFactPersistenceFailure
import com.paycore.financial.paymentfact.application.required.PaymentFactProjectionStore
import com.paycore.financial.paymentfact.domain.AuthorizedPaymentFact
import com.paycore.financial.paymentfact.domain.CancelledPaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFactInboxOutcome
import com.paycore.financial.paymentfact.domain.PaymentFactProjectionRule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.UnexpectedRollbackException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 결제 사실 Inbox 수신(FIN-FACT-RELAY-001 §4.2). FINANCIAL 로컬 TX(READ COMMITTED) 하나에서:
 * ```
 * (지원 사실) 결제별 직렬화 행 IODKU → FOR UPDATE
 *   → Inbox event_id 조회: 있으면 같은 원문이면 DUPLICATE(처음 결과), 다르면 EventIdConflict
 *   → Inbox effect_key 조회: 다른 eventId가 이미 기록했으면 EffectKeyConflict
 *   → 이력 읽기 → 투영 판정(보류 사유) → Inbox INSERT + 사실 INSERT + 보류 INSERT + 직렬화 행 갱신
 * (지원하지 않는 eventType/schemaVersion) Inbox 조회 → UNSUPPORTED로 Inbox INSERT만(투영 없음 — 원문 보존·경보)
 * COMMIT → 결과(ACK)
 * ```
 * 같은 Payment의 사실은 직렬화 행 잠금으로 줄을 서므로 조회 뒤 INSERT가 경쟁하지 않는다. 다른 Payment가 같은 eventId/effect key를 주장하는 비정상 경합과
 * UNSUPPORTED 동시 수신은 UNIQUE 충돌 → 새 TX에서 재판정으로 수렴한다(DUPLICATE 또는 Conflict). 교착·충돌은 새 TX로 같은 의도를 다시 실행한다(최대 3회).
 * 충돌·중복 판정 TX는 아무것도 남기지 않도록 rollback한다(직렬화 행 IODKU 포함).
 */
@Service
class PaymentFactInboxService(
    transactionManager: PlatformTransactionManager,
    private val inbox: PaymentFactInboxStore,
    private val projection: PaymentFactProjectionStore,
    private val clock: PaymentFactClock,
) : ReceivePaymentFactUseCase {

    private val transactions = PaymentFactLocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val log = LoggerFactory.getLogger(PaymentFactInboxService::class.java)

    override fun receive(envelope: InboundPaymentFact): ReceivePaymentFactResult {
        envelope.fact?.let {
            check(it.effectKey == envelope.effectKey) { "the envelope effect key must be the fact's effect key (checked by the parser)" }
        }
        val result = try {
            transactions.execute(::isRetryable) { status -> once(envelope, status) }
        } catch (e: PaymentFactCommitOutcomeUnknownException) {
            throw ReceivePaymentFactFailure("commit outcome unknown", e)
        } catch (e: PaymentFactPersistenceFailure) {
            throw ReceivePaymentFactFailure("temporarily unavailable", e)
        } catch (e: CannotCreateTransactionException) {
            throw ReceivePaymentFactFailure("temporarily unavailable", e)
        } catch (e: UnexpectedRollbackException) {
            throw ReceivePaymentFactFailure("temporarily unavailable", e)
        }
        logResult(envelope, result)
        return result
    }

    private fun once(envelope: InboundPaymentFact, status: TransactionStatus): ReceivePaymentFactResult {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val fact = envelope.fact
        val owner = fact?.let { projection.lockStream(it.identity, now) }
        inbox.findByEventId(envelope.eventId)?.let { stored ->
            status.setRollbackOnly()
            return if (stored.payloadSha256 == envelope.rawSha256) {
                ReceivePaymentFactResult.Stored(stored.outcome, duplicate = true)
            } else {
                ReceivePaymentFactResult.EventIdConflict
            }
        }
        inbox.findByEffectKey(envelope.effectKey)?.let {
            status.setRollbackOnly()
            return ReceivePaymentFactResult.EffectKeyConflict
        }
        if (fact == null) {
            inbox.insert(record(envelope, PaymentFactInboxOutcome.UNSUPPORTED, null, now))
            return ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.UNSUPPORTED, duplicate = false)
        }
        val history = projection.history(fact.identity.paymentId, owner)
        val decision = PaymentFactProjectionRule.decide(history, fact)
        inbox.insert(record(envelope, decision.outcome, fact, now))
        when (fact) {
            is AuthorizedPaymentFact -> projection.insertAuthorization(fact, envelope.eventId, now)
            is CancelledPaymentFact -> projection.insertCancellation(
                fact, history.authorizations.any { it.transactionId == fact.originalTransactionId }, envelope.eventId, now,
            )
        }
        if (decision.holds.isNotEmpty()) projection.insertHolds(fact, decision.holds, envelope.eventId, now)
        projection.recordFact(fact.identity.paymentId, now)
        if (decision.holds.isNotEmpty()) {
            log.warn(
                "payment fact held eventId={} eventType={} paymentId={} transactionId={} reasons={}",
                envelope.eventId, envelope.eventType, fact.identity.paymentId, fact.transactionId, decision.holds.joinToString(","),
            )
        }
        return ReceivePaymentFactResult.Stored(decision.outcome, duplicate = false)
    }

    private fun record(envelope: InboundPaymentFact, outcome: PaymentFactInboxOutcome, fact: PaymentFact?, now: Instant) = PaymentFactInboxRecord(
        eventId = envelope.eventId, effectKey = envelope.effectKey, eventType = envelope.eventType, schemaVersion = envelope.schemaVersion,
        producer = envelope.producer, aggregateType = envelope.aggregateType, aggregateId = envelope.aggregateId, aggregateVersion = envelope.aggregateVersion,
        occurredAt = envelope.occurredAt, outcome = outcome, paymentId = fact?.identity?.paymentId, transactionId = fact?.transactionId,
        payload = envelope.rawBody, payloadSha256 = envelope.rawSha256, signingKeyId = envelope.signingKeyId, receivedAt = now,
    )

    private fun logResult(envelope: InboundPaymentFact, result: ReceivePaymentFactResult) {
        when (result) {
            is ReceivePaymentFactResult.Stored -> when {
                result.duplicate -> log.info("payment fact duplicate eventId={} originalOutcome={}", envelope.eventId, result.outcome)
                result.outcome == PaymentFactInboxOutcome.UNSUPPORTED -> log.error(
                    "payment fact unsupported (stored without projection) eventId={} eventType={} schemaVersion={}",
                    envelope.eventId, LogSafe.token(envelope.eventType), envelope.schemaVersion,
                )
                else -> log.info("payment fact received eventId={} eventType={} outcome={}", envelope.eventId, envelope.eventType, result.outcome)
            }
            ReceivePaymentFactResult.EventIdConflict -> log.error("payment fact eventId reused with a different body eventId={}", envelope.eventId)
            ReceivePaymentFactResult.EffectKeyConflict -> log.error("payment fact effect key claimed by another eventId eventId={}", envelope.eventId)
        }
    }

    private fun isRetryable(e: RuntimeException): Boolean =
        e is PaymentFactPersistenceFailure.Deadlock || e is PaymentFactPersistenceFailure.OptimisticConflict ||
            e is PaymentFactPersistenceFailure.UniqueConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/** 로그 값 정리: 봉투에서 온 문자열을 로그에 넣을 때 영숫자·`._:-`만 남기고 64자로 자른다(로그 주입 방지). */
internal object LogSafe {
    private val UNSAFE = Regex("[^A-Za-z0-9._:-]")

    fun token(value: String): String = UNSAFE.replace(value, "?").take(64)
}
