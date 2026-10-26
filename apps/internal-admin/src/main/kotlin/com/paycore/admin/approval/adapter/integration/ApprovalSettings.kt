package com.paycore.admin.approval.adapter.integration

import com.paycore.admin.approval.application.required.ApprovalEnvironment
import org.springframework.boot.context.properties.ConfigurationProperties
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/**
 * 승인 설정(`paycore.admin.approval` — D-SV **Demo 값, 운영값 아님**: 요청 유효 24시간, 만료 정리 주기). 기본값 없음. 승인 ID는 `apr_` + ULID(48비트 ms + 80비트 SecureRandom).
 */
@ConfigurationProperties(prefix = "paycore.admin.approval")
class ApprovalSettings(override val validity: Duration, val expiryJob: ExpiryJob) : ApprovalEnvironment {
    private val random = SecureRandom()

    init {
        require(!validity.isNegative && !validity.isZero) { "paycore.admin.approval.validity must be positive" }
    }

    class ExpiryJob(val enabled: Boolean, val fixedDelay: Duration)

    override fun now(): Instant = Instant.now()

    override fun newApprovalId(): String {
        val chars = CharArray(ULID_LENGTH)
        var t = System.currentTimeMillis()
        for (i in TIME_CHARS - 1 downTo 0) {
            chars[i] = ALPHABET[(t and 0x1F).toInt()]
            t = t ushr 5
        }
        var buffer = 0
        var bits = 0
        var index = TIME_CHARS
        for (b in ByteArray(RANDOM_BYTES).also(random::nextBytes)) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                chars[index++] = ALPHABET[(buffer ushr (bits - 5)) and 0x1F]
                bits -= 5
            }
        }
        return "apr_" + String(chars)
    }

    override fun toString(): String = "ApprovalSettings(validity=$validity, expiryJob.enabled=${expiryJob.enabled})"

    private companion object {
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val ULID_LENGTH = 26
        const val TIME_CHARS = 10
        const val RANDOM_BYTES = 10
    }
}
