package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.EnrollmentCodeGenerator
import com.paycore.admin.account.application.required.IssuedEnrollmentCode
import com.paycore.admin.account.domain.Base32
import com.paycore.admin.account.domain.EnrollmentCodeHash
import java.security.SecureRandom

/** 160비트 `SecureRandom` → RFC 4648 base32(대문자, padding 없음) 32자. 저장은 SHA-256만(§4.3.6). */
class SecureRandomEnrollmentCodeGenerator : EnrollmentCodeGenerator {
    private val random = SecureRandom()

    override fun newCode(): IssuedEnrollmentCode {
        val code = Base32.encode(ByteArray(CODE_BYTES).also(random::nextBytes))
        return IssuedEnrollmentCode(code, EnrollmentCodeHash.of(code))
    }

    private companion object {
        const val CODE_BYTES = 20
    }
}
