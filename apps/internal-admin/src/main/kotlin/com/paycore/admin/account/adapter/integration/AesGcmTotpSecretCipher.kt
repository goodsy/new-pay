package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.CipherContext
import com.paycore.admin.account.application.required.SealedSecret
import com.paycore.admin.account.application.required.SecretUnsealFailure
import com.paycore.admin.account.application.required.TotpSecretCipher
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM(nonce 96비트 무작위, tag 128비트 — D-07). 출력 = nonce ‖ 암호문 ‖ tag(시크릿 20바이트 → 48바이트, DB VARBINARY(64)).
 * AAD = 길이 접두(UTF-8) 문맥 태그 ‖ 키 버전(int) ‖ 부분 개수 ‖ 각 부분 길이 접두 — 다른 actor·진행 ID·용도·키 버전으로는 tag 검증이 실패한다(rev 0.4 n-1).
 * 키는 설정의 버전별 256비트 값(시험 키는 시험 설정에만). LIVE에서 알려진 Demo 키·키 없음이면 기동 실패하는 가드는 S4(n-4).
 */
class AesGcmTotpSecretCipher(keys: Map<Int, ByteArray>, private val currentKeyVersion: Int) : TotpSecretCipher {

    private val keys: Map<Int, SecretKeySpec> = keys.mapValues { (version, key) ->
        require(version >= 1) { "key versions start at 1" }
        require(key.size == KEY_BYTES) { "TOTP cipher keys must be 256-bit" }
        SecretKeySpec(key.copyOf(), "AES")
    }
    private val random = SecureRandom()

    init {
        require(currentKeyVersion in this.keys) { "the current TOTP key version must be configured" }
    }

    override fun encrypt(plaintext: ByteArray, context: CipherContext): SealedSecret {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.getValue(currentKeyVersion), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(context, currentKeyVersion))
        return SealedSecret(currentKeyVersion, nonce + cipher.doFinal(plaintext))
    }

    override fun decrypt(sealed: SealedSecret, context: CipherContext): ByteArray {
        val key = keys[sealed.keyVersion] ?: throw SecretUnsealFailure(null)
        val bytes = sealed.bytes
        if (bytes.size <= NONCE_BYTES + TAG_BITS / 8) throw SecretUnsealFailure(null)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
            cipher.updateAAD(aad(context, sealed.keyVersion))
            cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            throw SecretUnsealFailure(e)
        }
    }

    private fun aad(context: CipherContext, keyVersion: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            fun part(value: String) = value.toByteArray(Charsets.UTF_8).let { out.writeInt(it.size); out.write(it) }
            part(context.tag)
            out.writeInt(keyVersion)
            out.writeInt(context.parts.size)
            context.parts.forEach(::part)
        }
        return buffer.toByteArray()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
