package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.CheckoutSecretGenerator
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.domain.AuthenticationRef
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.CheckoutHandle
import com.paycore.payment.payment.domain.CheckoutSessionId
import com.paycore.payment.payment.domain.CheckoutTokenId
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * 운영 결제창 비밀·식별자 생성기(JDK [SecureRandom]만 — [SecureRandomPaymentIdentifierGenerator]와 같은 관례). 출력은 구성상 ASCII `[A-Za-z0-9_-]`다.
 * 식별자(ULID)의 시각 부분은 비밀이 아니고 추측 불가성은 80bit 난수가 담당한다. 비밀(토큰·쿠키·state)은 256bit, handle·인증 참조는 128bit 난수 전체다.
 */
@Component
class SecureRandomCheckoutSecretGenerator(private val clock: PaymentClock) : CheckoutSecretGenerator {

    private val random = SecureRandom()

    override fun newTokenSecret(): String = TOKEN_PREFIX + randomBase64Url(SECRET_BYTES)

    override fun newTokenId(): CheckoutTokenId = CheckoutTokenId("ctk_" + ulid(clock.now()))

    override fun newCheckoutSessionId(): CheckoutSessionId = CheckoutSessionId("cks_" + ulid(clock.now()))

    override fun newHandle(): CheckoutHandle = CheckoutHandle(randomBase64Url(HANDLE_BYTES))

    override fun newSessionSecret(): String = randomBase64Url(SECRET_BYTES)

    override fun newAuthenticationSessionId(): AuthenticationSessionId = AuthenticationSessionId("aus_" + ulid(clock.now()))

    override fun newState(): String = randomBase64Url(SECRET_BYTES)

    override fun newAuthenticationRef(): AuthenticationRef = AuthenticationRef("aref_" + randomBase64Url(HANDLE_BYTES))

    private fun randomBase64Url(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private fun ulid(at: Instant): String {
        val millis = at.toEpochMilli()
        require(millis in 0..MAX_ULID_MILLIS) { "time outside the ULID range" }
        val chars = CharArray(ULID_LENGTH)
        for (i in 0 until TIME_CHARS) chars[TIME_CHARS - 1 - i] = CROCKFORD[((millis ushr (5 * i)) and 0x1F).toInt()]
        var acc = 0L
        var bits = 0
        var out = TIME_CHARS
        for (b in ByteArray(RANDOM_BYTES).also(random::nextBytes)) {
            acc = (acc shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                chars[out++] = CROCKFORD[((acc ushr bits) and 0x1F).toInt()]
            }
        }
        return String(chars)
    }

    private companion object {
        const val TOKEN_PREFIX = "ckt_"
        const val SECRET_BYTES = 32
        const val HANDLE_BYTES = 16
        const val RANDOM_BYTES = 10
        const val TIME_CHARS = 10
        const val ULID_LENGTH = 26
        const val MAX_ULID_MILLIS = (1L shl 48) - 1
        const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
