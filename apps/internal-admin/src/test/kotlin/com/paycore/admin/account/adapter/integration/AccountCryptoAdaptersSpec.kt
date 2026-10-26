package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.CipherContext
import com.paycore.admin.account.application.required.SealedSecret
import com.paycore.admin.account.application.required.SecretUnsealFailure
import com.paycore.admin.account.domain.EnrollmentCodeHash
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64

/**
 * 계정 암호 adapter(§4.3.3·§4.3.4·§4.3.6, D-03·D-04·D-07, n-2). Spring 없이 adapter를 직접 만든다.
 * TOTP는 RFC 6238 golden(harness `test_totp_rfc6238_golden.py`가 Python hmac으로 독립 확인)으로 검사한다.
 */
class AccountCryptoAdaptersSpec : FunSpec({

    val golden = JsonMapper.builder().build().readTree(AccountCryptoAdaptersSpec::class.java.getResource("/contracts/totp-rfc6238.golden.json")!!.readText())
    val rfcSecret = golden["secretAscii"].asString().toByteArray(Charsets.US_ASCII)
    val verifier = Rfc6238TotpVerifier()

    test("TOTP: every RFC 6238 golden vector matches at its exact time and step (6 digits, SHA-1, 30 s)") {
        golden["vectors"].forEach { v ->
            val at = Instant.ofEpochSecond(v["unixTime"].asLong())
            verifier.stepAt(at) shouldBe v["step"].asLong()
            verifier.matchingStep(rfcSecret, v["code6"].asString(), at, windowSteps = 0) shouldBe v["step"].asLong()
        }
    }

    test("TOTP: the window accepts the previous and next step only (±1), not further") {
        val v = golden["vectors"][3]
        val step = v["step"].asLong()
        val code = v["code6"].asString()
        listOf(-60L, -30L, 0L, 30L, 60L).map { verifier.matchingStep(rfcSecret, code, Instant.ofEpochSecond(step * 30 + it), windowSteps = 1) } shouldBe
            listOf(null, step, step, step, null)
    }

    test("TOTP: a wrong code, a non-6-digit code or another secret does not match") {
        val v = golden["vectors"][0]
        val at = Instant.ofEpochSecond(v["unixTime"].asLong())
        listOf(
            verifier.matchingStep(rfcSecret, "000000", at, 1),
            verifier.matchingStep(rfcSecret, v["code8"].asString(), at, 1),
            verifier.matchingStep(rfcSecret, " " + v["code6"].asString().drop(1), at, 1),
            verifier.matchingStep("another-secret-20byt".toByteArray(), v["code6"].asString(), at, 1),
        ) shouldBe listOf(null, null, null, null)
    }

    val key1 = ByteArray(32) { 1 }
    val key2 = ByteArray(32) { 2 }
    val cipher = AesGcmTotpSecretCipher(mapOf(1 to key1, 2 to key2), currentKeyVersion = 2)
    val context = CipherContext(CipherContext.STORED_TOTP, listOf("adm_00000000000000000000000001"))

    test("cipher: round trip with the current key version; every encryption uses a fresh nonce") {
        val secret = ByteArray(20) { it.toByte() }
        val a = cipher.encrypt(secret, context)
        val b = cipher.encrypt(secret, context)
        (a.keyVersion to a.bytes.size) shouldBe (2 to 48)
        a.bytes.contentEquals(b.bytes) shouldBe false
        cipher.decrypt(a, context).contentEquals(secret) shouldBe true
    }

    test("cipher: another actor, another tag or another part order cannot decrypt (AAD binding)") {
        val sealed = cipher.encrypt(ByteArray(20), context)
        listOf(
            CipherContext(CipherContext.STORED_TOTP, listOf("adm_00000000000000000000000002")),
            CipherContext(CipherContext.ENROLLMENT_PENDING, listOf("adm_00000000000000000000000001")),
            CipherContext(CipherContext.STORED_TOTP, listOf("adm_0000000000000000000000000", "1")),
        ).forEach { other -> shouldThrow<SecretUnsealFailure> { cipher.decrypt(sealed, other) } }
    }

    test("cipher: tampered bytes, a relabelled key version or an unknown key version fail closed") {
        val sealed = cipher.encrypt(ByteArray(20), context)
        val tampered = sealed.bytes.also { it[20] = (it[20].toInt() xor 1).toByte() }
        shouldThrow<SecretUnsealFailure> { cipher.decrypt(SealedSecret(2, tampered), context) }
        shouldThrow<SecretUnsealFailure> { cipher.decrypt(SealedSecret(1, sealed.bytes), context) }
        shouldThrow<SecretUnsealFailure> { cipher.decrypt(SealedSecret(9, sealed.bytes), context) }
        AesGcmTotpSecretCipher(mapOf(1 to key1, 2 to key2), currentKeyVersion = 1).decrypt(sealed, context).size shouldBe 20
    }

    test("cipher: keys must be 256-bit and the current version must exist") {
        shouldThrow<IllegalArgumentException> { AesGcmTotpSecretCipher(mapOf(1 to ByteArray(16)), 1) }
        shouldThrow<IllegalArgumentException> { AesGcmTotpSecretCipher(mapOf(1 to key1), 2) }
    }

    test("password: BCrypt with the {bcrypt} prefix and the configured cost; match, mismatch and missing hash (dummy compare) behave") {
        val hasher = BCryptPasswordHasher(cost = 4)
        val hash = hasher.hash("correct horse battery".toCharArray())
        hash.value shouldStartWith "{bcrypt}\$2a\$04\$"
        listOf(
            hasher.matches("correct horse battery".toCharArray(), hash),
            hasher.matches("wrong horse battery".toCharArray(), hash),
            hasher.matches("correct horse battery".toCharArray(), null),
        ) shouldBe listOf(true, false, false)
        hasher.dummyHashCost() shouldBe 4
        hasher.hash("correct horse battery".toCharArray()) shouldNotBe hash
    }

    test("enrollment codes: 160-bit, upper-case base32 32 characters, hash = SHA-256 of the normalized code, unique") {
        val generator = SecureRandomEnrollmentCodeGenerator()
        val codes = List(500) { generator.newCode() }
        codes.forEach { c ->
            c.displayCode.length shouldBe 32
            EnrollmentCodeHash.normalize(c.displayCode) shouldBe c.displayCode
            c.hash shouldBe EnrollmentCodeHash.of(c.displayCode)
        }
        codes.map { it.displayCode }.toSet().size shouldBe 500
        SecureRandomTotpSecretGenerator().newSecret().size shouldBe 20
    }

    test("login-name digest: HMAC-SHA256 with the configured key (RFC 4231 test case 2)") {
        val digester = HmacLoginNameDigester("Jefe".toByteArray())
        digester.digest("what do ya want for nothing?").joinToString("") { "%02x".format(it) } shouldBe
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        Base64.getEncoder().encodeToString(HmacLoginNameDigester(ByteArray(32)).digest("ops.kim")).length shouldBe 44
    }

    test("password over 72 bytes: Spring Security 7.1.1 BCrypt refuses to hash it but MATCHES a 73-byte input against the hash of its 72-byte prefix (truncation) — the login service must cut it off before comparing (S4 review m-3)") {
        val hasher = BCryptPasswordHasher(4)
        val hash72 = hasher.hash("a".repeat(72).toCharArray())
        shouldThrow<IllegalArgumentException> { hasher.hash("a".repeat(73).toCharArray()) }
        hasher.matches("a".repeat(73).toCharArray(), hash72) shouldBe true
    }
})
