package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.IdempotencyRecordId
import com.paycore.payment.payment.application.required.AuthorizationIdentifierGenerator
import com.paycore.payment.payment.application.required.AuthorizationIdentifiers
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.PaymentTransactionId
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * 승인 경로 식별자(JDK SecureRandom). ULID = 시각 48bit + 난수 80bit(Crockford base32 26자) — 기관 요청 ID `ar_`(CA-22)는 기관이 요청 연령을 읽는 값이기도 하다
 * (가상 VAN — AUTH-001 §16.6 결정 (2)). 멱등 기록 ID는 UUID v4.
 */
@Component
class SecureRandomAuthorizationIdentifierGenerator : AuthorizationIdentifierGenerator {
    private val random = SecureRandom()

    override fun next(at: Instant): AuthorizationIdentifiers =
        AuthorizationIdentifiers(AuthorizationId("pau_" + ulid(at)), AttemptId("pat_" + ulid(at)), "ar_" + ulid(at))

    override fun idempotencyRecordId(): IdempotencyRecordId = IdempotencyRecordId(UUID.randomUUID().toString())

    override fun inquiryId(at: Instant): String = "aq_" + ulid(at)

    override fun transactionId(at: Instant): PaymentTransactionId = PaymentTransactionId("ptx_" + ulid(at))

    private fun ulid(at: Instant): String {
        val out = CharArray(LENGTH)
        var t = at.toEpochMilli()
        for (i in TIME_CHARS - 1 downTo 0) {
            out[i] = ALPHABET[(t and MASK).toInt()]
            t = t ushr BITS
        }
        for (i in TIME_CHARS until LENGTH) out[i] = ALPHABET[random.nextInt(ALPHABET.length)]
        return String(out)
    }

    private companion object {
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val LENGTH = 26
        const val TIME_CHARS = 10
        const val BITS = 5
        const val MASK = 0x1FL
    }
}
