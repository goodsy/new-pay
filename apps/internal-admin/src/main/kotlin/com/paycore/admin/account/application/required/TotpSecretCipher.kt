package com.paycore.admin.account.application.required

/**
 * TOTP 시크릿·등록 대기 값 암호화 Port(AES-256-GCM — D-07). AAD는 [CipherContext]로 묶는다: adapter가 문맥 태그·키 버전·각 부분을 길이 접두로
 * 인코딩한다(rev 0.4 n-1 — 경계 모호성 없음). 다른 문맥(다른 actor·다른 등록 진행 ID·다른 용도)으로는 복호가 실패한다.
 */
interface TotpSecretCipher {
    fun encrypt(plaintext: ByteArray, context: CipherContext): SealedSecret

    /** 문맥·키·변조가 맞지 않으면 [SecretUnsealFailure]. */
    fun decrypt(sealed: SealedSecret, context: CipherContext): ByteArray
}

/** 암호문(nonce ‖ 암호문 ‖ tag)과 그 키 버전. toString은 값을 내지 않는다. */
class SealedSecret(val keyVersion: Int, bytes: ByteArray) {
    private val value = bytes.copyOf()

    val bytes: ByteArray get() = value.copyOf()

    override fun toString(): String = "SealedSecret(keyVersion=$keyVersion, ***)"
}

/**
 * 암호 문맥. [tag]: 용도별 고정 문자열(저장 TOTP = [STORED_TOTP], 등록 대기 값 = [ENROLLMENT_PENDING]), [parts]: 묶을 값들(순서 의미 있음).
 */
data class CipherContext(val tag: String, val parts: List<String>) {
    init {
        require(tag.isNotBlank() && parts.isNotEmpty()) { "cipher context needs a tag and at least one part" }
    }

    companion object {
        const val STORED_TOTP = "paycore.admin.totp.v1"
        const val ENROLLMENT_PENDING = "paycore.admin.enroll-pending.v1"
    }
}

class SecretUnsealFailure(cause: Throwable?) : RuntimeException("secret could not be unsealed (context, key or integrity mismatch)", cause)
