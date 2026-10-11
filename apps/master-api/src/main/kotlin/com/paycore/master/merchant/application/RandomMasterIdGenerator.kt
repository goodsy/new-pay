package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.required.MasterIdGenerator
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * 서버 생성 식별자(관례, 명세 §6): `<prefix>_` + ULID 26자(48bit ms 시각 + 80bit 난수, Crockford base32 대문자). 순번이 아니고 추측하기 어렵다
 * (난수 80bit — 같은 ms 안 충돌 확률은 무시할 수준이며 그래도 충돌하면 PK가 막고 IdentifierCollision으로 드러난다). 외부 기술 의존이 없어
 * application에 둔다(JDK `SecureRandom`·`Clock`만).
 */
@Component
class RandomMasterIdGenerator : MasterIdGenerator {
    private val random = SecureRandom()
    private val clock: Clock = Clock.systemUTC()

    override fun newId(prefix: String): String {
        val time = clock.millis()
        val bytes = ByteArray(RANDOM_BYTES).also(random::nextBytes)
        val chars = CharArray(ULID_LENGTH)
        // 앞 10자: 48bit 시각(5bit씩, 50bit 중 상위 2bit는 0).
        for (i in 0 until TIME_CHARS) chars[i] = CROCKFORD[((time ushr (5 * (TIME_CHARS - 1 - i))) and 0x1F).toInt()]
        // 뒤 16자: 80bit 난수.
        var buffer = 0
        var bits = 0
        var index = TIME_CHARS
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                chars[index++] = CROCKFORD[(buffer ushr (bits - 5)) and 0x1F]
                bits -= 5
            }
        }
        return "${prefix}_${String(chars)}"
    }

    override fun newUuid(): String = UUID.randomUUID().toString()

    private companion object {
        const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val ULID_LENGTH = 26
        const val TIME_CHARS = 10
        const val RANDOM_BYTES = 10
    }
}
