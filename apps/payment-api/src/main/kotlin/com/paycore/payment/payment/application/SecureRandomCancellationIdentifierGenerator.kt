package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.CancellationIdentifierGenerator
import com.paycore.payment.payment.application.required.NewCancellationIdentifiers
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancellationId
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Instant

/**
 * 취소 경로 식별자(JDK SecureRandom, CANCEL-001 CN-07). ULID = 시각 48bit + 난수 80bit(Crockford base32 26자) — 기관 취소 요청 ID `cr_`·조회 ID `cq_`는 가상 VAN이 요청 연령을 읽는
 * 값이기도 하다(sim-van-2 — 승인 `ar_`·`aq_`와 같은 규칙).
 */
@Component
class SecureRandomCancellationIdentifierGenerator : CancellationIdentifierGenerator {
    private val random = SecureRandom()

    override fun next(at: Instant): NewCancellationIdentifiers =
        NewCancellationIdentifiers(CancellationId("pcn_" + ulid(at)), CancelAttemptId("pca_" + ulid(at)), "cr_" + ulid(at))

    override fun inquiryId(at: Instant): String = "cq_" + ulid(at)

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
