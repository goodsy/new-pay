package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.TotpSecretGenerator
import java.security.SecureRandom

/** TOTP 시크릿 160비트(RFC 4226 권장 길이 — §4.3.4). */
class SecureRandomTotpSecretGenerator : TotpSecretGenerator {
    private val random = SecureRandom()

    override fun newSecret(): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

    private companion object {
        const val SECRET_BYTES = 20
    }
}
