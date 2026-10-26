package com.paycore.admin.account.domain

/**
 * 저장된 TOTP 시크릿(암호문 — AES-256-GCM, AAD = actor ID + 키 버전, D-07)과 키 버전. 평문 시크릿은 domain에 없다.
 * toString은 값을 내지 않는다.
 */
class TotpCredential(ciphertext: ByteArray, val keyVersion: Int) {
    private val bytes: ByteArray = ciphertext.copyOf()

    init {
        require(bytes.size in MIN_LENGTH..MAX_LENGTH) { "TOTP ciphertext must be $MIN_LENGTH-$MAX_LENGTH bytes" }
        require(keyVersion >= 1) { "TOTP key version must be positive" }
    }

    val ciphertext: ByteArray get() = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is TotpCredential && other.keyVersion == keyVersion && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * keyVersion + bytes.contentHashCode()

    override fun toString(): String = "TotpCredential(keyVersion=$keyVersion, ***)"

    private companion object {
        /** nonce 12 + 시크릿 20 + tag 16 = 48(DB VARBINARY(64)). */
        const val MIN_LENGTH = 29
        const val MAX_LENGTH = 64
    }
}
