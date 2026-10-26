package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.PasswordHasher
import com.paycore.admin.account.domain.PasswordHash
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.nio.CharBuffer
import java.security.SecureRandom

/**
 * BCrypt(`{bcrypt}` 접두 — D-03, Argon2는 BOM 밖 F-11). cost는 설정(Demo 12). 해시가 없을 때(없는 계정·자격 없는 계정)도 같은 cost의 **더미 해시**와
 * 한 번 비교하고 false — 어떤 분기든 BCrypt 비교가 정확히 1회(경로 동등성 m-4).
 */
class BCryptPasswordHasher(cost: Int) : PasswordHasher {
    private val encoder = BCryptPasswordEncoder(cost, SecureRandom())
    private val dummyHash: String = encoder.encode(CharBuffer.wrap(CharArray(24) { 'x' }))!!

    override fun hash(raw: CharArray): PasswordHash = PasswordHash(PREFIX + encoder.encode(CharBuffer.wrap(raw)))

    override fun matches(raw: CharArray, hash: PasswordHash?): Boolean {
        val encoded = hash?.value?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
        val compared = encoder.matches(CharBuffer.wrap(raw), encoded ?: dummyHash)
        return encoded != null && compared
    }

    /** 시험용: 더미 해시가 설정 cost로 만들어졌는지(`$2a$<cost>$`). */
    fun dummyHashCost(): Int = dummyHash.split('$')[2].toInt()

    private companion object {
        const val PREFIX = "{bcrypt}"
    }
}
