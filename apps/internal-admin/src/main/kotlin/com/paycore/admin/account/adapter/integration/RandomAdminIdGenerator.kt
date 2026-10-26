package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.AdminIdGenerator
import com.paycore.admin.account.domain.AdminActorId
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock

/**
 * ULID(48비트 ms 시각 + 80비트 `SecureRandom`, Crockford base32 26자) 기반 서버 식별자. actor ID `adm_`·등록 발급 ID `enc_`.
 * 진행 ID는 128비트 난수 hex(추측 불가 — 세션 안 진행 상태 식별, §4.3.6).
 */
@Component
class RandomAdminIdGenerator : AdminIdGenerator {
    private val random = SecureRandom()
    private val clock: Clock = Clock.systemUTC()

    override fun newActorId(): AdminActorId = AdminActorId(AdminActorId.PREFIX + ulid())

    override fun newEnrollIssueId(): String = "enc_" + ulid()

    override fun newProgressId(): String = ByteArray(PROGRESS_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    internal fun ulid(): String {
        val time = clock.millis()
        val randomBits = ByteArray(RANDOM_BYTES).also(random::nextBytes)
        val chars = CharArray(ULID_LENGTH)
        var t = time
        for (i in TIME_CHARS - 1 downTo 0) {
            chars[i] = ALPHABET[(t and 0x1F).toInt()]
            t = t ushr 5
        }
        // 80비트 난수 → 16자(5비트씩).
        var buffer = 0
        var bits = 0
        var index = TIME_CHARS
        for (b in randomBits) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                chars[index++] = ALPHABET[(buffer ushr (bits - 5)) and 0x1F]
                bits -= 5
            }
        }
        return String(chars)
    }

    private companion object {
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val ULID_LENGTH = 26
        const val TIME_CHARS = 10
        const val RANDOM_BYTES = 10
        const val PROGRESS_BYTES = 16
    }
}
