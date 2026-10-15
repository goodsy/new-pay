package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.EventId
import com.paycore.payment.payment.application.provided.IdempotencyRecordId
import com.paycore.payment.payment.application.provided.NewPaymentIdentifiers
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentIdentifierGenerator
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentKey
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * 운영 식별자 생성기(CP-API-001 명세 §3.4, JDK만 — MASTER `RandomMasterIdGenerator`와 같은 관례).
 *
 * - paymentId: `pay_` + ULID 26자(Crockford base32 대문자: 48bit ms 시각 + 80bit [SecureRandom]). 시각 부분은 응답의 paymentCreatedAt과
 *   같은 정보이고 추측 불가성은 80bit 난수가 담당한다.
 * - paymentKey: `pk_` + base64url(무패딩) 256bit [SecureRandom] = 46자(계약 §5 "128bit 이상 난수"). 자격 증명이 아니다.
 * - 멱등 기록 ID·eventId: UUID v4(소문자 표준 표기).
 *
 * 출력은 구성상 ASCII `[A-Za-z0-9_-]`라 짝 없는 서로게이트가 생길 수 없다. 그래도 domain 값 객체의 서로게이트 검사는 유지한다(최후 방어선).
 */
@Component
class SecureRandomPaymentIdentifierGenerator(private val clock: PaymentClock) : PaymentIdentifierGenerator {

    private val random = SecureRandom()

    override fun newIdentifiers(): NewPaymentIdentifiers = NewPaymentIdentifiers(
        paymentId = PaymentId(PAYMENT_ID_PREFIX + ulid(clock.now())),
        paymentKey = PaymentKey(PAYMENT_KEY_PREFIX + randomBase64Url(PAYMENT_KEY_BYTES)),
        idempotencyRecordId = IdempotencyRecordId(UUID.randomUUID().toString()),
        eventId = EventId(UUID.randomUUID().toString()),
    )

    private fun ulid(at: Instant): String {
        val millis = at.toEpochMilli()
        require(millis in 0..MAX_ULID_MILLIS) { "time outside the ULID range" }
        val randomBits = ByteArray(RANDOM_BYTES).also(random::nextBytes)
        val chars = CharArray(ULID_LENGTH)
        // 48bit 시각 → 10자(앞 2bit는 0).
        for (i in 0 until TIME_CHARS) {
            chars[TIME_CHARS - 1 - i] = CROCKFORD[((millis ushr (5 * i)) and 0x1F).toInt()]
        }
        // 80bit 난수 → 16자.
        var acc = 0L
        var bits = 0
        var out = TIME_CHARS
        for (b in randomBits) {
            acc = (acc shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                chars[out++] = CROCKFORD[((acc ushr bits) and 0x1F).toInt()]
            }
        }
        return String(chars)
    }

    private fun randomBase64Url(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private companion object {
        const val PAYMENT_ID_PREFIX = "pay_"
        const val PAYMENT_KEY_PREFIX = "pk_"
        const val PAYMENT_KEY_BYTES = 32
        const val RANDOM_BYTES = 10
        const val TIME_CHARS = 10
        const val ULID_LENGTH = 26
        const val MAX_ULID_MILLIS = (1L shl 48) - 1
        const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}

/** 운영 서버 시계(UTC Instant). */
@Component
class SystemPaymentClock : PaymentClock {
    override fun now(): Instant = Instant.now()
}
