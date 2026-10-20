package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.application.required.PaymentClock
import java.security.SecureRandom

/** 메시지 ID(송신자 생성 ULID — 시각 48bit + 난수 80bit, Crockford base32). 승인 Adapter에서 옮김(CANCEL-001 §6.6 취소 Adapter와 공유, 동작 변경 없음). */
internal class SimVanMessageIds(private val clock: PaymentClock) {
    private val random = SecureRandom()

    fun next(): String {
        val out = CharArray(LENGTH)
        var t = clock.now().toEpochMilli()
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
