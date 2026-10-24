package com.paycore.simulator.merchant.application

import com.paycore.simulator.merchant.application.provided.MerchantWebhookReceiptQuery
import com.paycore.simulator.merchant.application.provided.ReceiveMerchantWebhookCommand
import com.paycore.simulator.merchant.application.provided.ReceiveMerchantWebhookResult
import com.paycore.simulator.merchant.application.provided.ReceiveMerchantWebhookUseCase
import com.paycore.simulator.merchant.application.required.DuplicateMerchantReceipt
import com.paycore.simulator.merchant.application.required.MerchantReceiver
import com.paycore.simulator.merchant.application.required.MerchantReceiverClock
import com.paycore.simulator.merchant.application.required.MerchantWebhookReceiptStore
import com.paycore.simulator.merchant.application.required.MerchantWebhookReceivers
import com.paycore.simulator.merchant.application.required.WebhookSignatureMatcher
import com.paycore.simulator.merchant.domain.MerchantWebhookReceipt
import com.paycore.simulator.merchant.domain.ReceiptOutcome
import com.paycore.simulator.merchant.domain.ReceivedEnvelope
import com.paycore.simulator.merchant.domain.ReceiverReply
import com.paycore.simulator.merchant.domain.WebhookSignatureCheck
import com.paycore.simulator.merchant.domain.WebhookVerdict
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/**
 * 가상 가맹점 수신기(WEBHOOK-001 §9, 검토 m-8·n-6 — Demo 대역). 순서: 수신기 확인 → 서명 판정(문법 → 창 → 서명) → 본문 봉투 → environment TEST → 시나리오 응답·중복 판정.
 * 요청마다 원장에 한 행을 append한다(본문 대신 SHA-256). 첫 2xx만 ACCEPTED(동시 첫 수신은 DB UNIQUE가 하나로 만든다 — 진 쪽은 새 TX에서 DUPLICATE).
 * 응답 지연은 TX가 끝난 뒤 web adapter가 기다린다.
 */
@Service
class MerchantWebhookReceiverService(
    private val receivers: MerchantWebhookReceivers,
    private val matcher: WebhookSignatureMatcher,
    private val receipts: MerchantWebhookReceiptStore,
    private val clock: MerchantReceiverClock,
    transactionManager: PlatformTransactionManager,
) : ReceiveMerchantWebhookUseCase, MerchantWebhookReceiptQuery {

    private val tx = TransactionTemplate(transactionManager)

    override fun receive(command: ReceiveMerchantWebhookCommand): ReceiveMerchantWebhookResult {
        val receiver = receivers.find(command.receiverId) ?: return ReceiveMerchantWebhookResult.UnknownReceiver
        val now = clock.now()
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance(SHA_256).digest(command.body))
        val verification = WebhookSignatureCheck.verify(command.signatureHeader, now, receivers.signatureWindow, receiver.keyIds) { kid, t, v1 ->
            matcher.matches(receiver.secret(kid), t, command.body, v1)
        }
        if (verification.verdict != WebhookVerdict.ACCEPTED) {
            return reject(receiver, ReceiptOutcome.rejectionOf(verification.verdict), null, null, digest, now)
        }
        val keyId = checkNotNull(verification.keyId)
        val envelope = command.envelope ?: return reject(receiver, ReceiptOutcome.REJECTED_BODY, null, keyId, digest, now)
        if (envelope.environment != TEST) return reject(receiver, ReceiptOutcome.REJECTED_ENVIRONMENT, envelope, keyId, digest, now)
        repeat(MAX_RACES) {
            try {
                return checkNotNull(tx.execute { decide(receiver, envelope, keyId, digest, now) })
            } catch (e: DuplicateMerchantReceipt) {
                // 같은 이벤트의 동시 첫 수신에 졌다 — 새 TX에서 DUPLICATE로 다시 판정한다.
            }
        }
        error("the first acceptance of an event kept racing")
    }

    override fun receipts(clientId: String, receiverId: String): List<MerchantWebhookReceipt>? {
        val receiver = receivers.find(receiverId)?.takeIf { it.clientId == clientId } ?: return null
        return tx.execute { receipts.list(receiver.id, MAX_LIST) }
    }

    private fun decide(receiver: MerchantReceiver, envelope: ReceivedEnvelope, keyId: String, digest: String, now: Instant): ReceiveMerchantWebhookResult {
        val reply = receiver.scenario.reply(receipts.countVerified(receiver.id, envelope.eventId))
        val outcome = when {
            !reply.accepted -> ReceiptOutcome.SCENARIO_REFUSED
            receipts.hasAccepted(receiver.id, envelope.eventId) -> ReceiptOutcome.DUPLICATE
            else -> ReceiptOutcome.ACCEPTED
        }
        receipts.append(MerchantWebhookReceipt(receiver.id, outcome, envelope, keyId, digest, reply.status, now))
        return ReceiveMerchantWebhookResult.Answered(outcome, reply)
    }

    private fun reject(
        receiver: MerchantReceiver, outcome: ReceiptOutcome, envelope: ReceivedEnvelope?, keyId: String?, digest: String, now: Instant,
    ): ReceiveMerchantWebhookResult {
        val status = checkNotNull(outcome.responseStatus)
        tx.executeWithoutResult { receipts.append(MerchantWebhookReceipt(receiver.id, outcome, envelope, keyId, digest, status, now)) }
        return ReceiveMerchantWebhookResult.Answered(outcome, ReceiverReply(status, Duration.ZERO, 0))
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val TEST = "TEST"
        const val MAX_RACES = 3
        const val MAX_LIST = 500
    }
}
