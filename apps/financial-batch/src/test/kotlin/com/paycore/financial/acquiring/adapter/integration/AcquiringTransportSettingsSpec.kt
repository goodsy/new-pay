package com.paycore.financial.acquiring.adapter.integration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

/**
 * 매입사 SFTP 설정 검증(ACQUIRING-001 2-D 리뷰 m-1·MJ-1 (c) — AQ-S1..S5). 운영 기본값 없음·키 내용 기동 파싱(fail-closed)·lease 식. 키는 시험마다 만든 임시 키쌍.
 */
class AcquiringTransportSettingsSpec : BehaviorSpec({

    val fingerprint = "SHA256:" + "A".repeat(43)

    fun ownerOnly(prefix: String): Path =
        Files.createTempFile(prefix, ".key", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))

    fun endpoint(key: Path, connect: Duration = Duration.ofSeconds(5), io: Duration = Duration.ofSeconds(10)) = AcquiringTransportSettings.Endpoint(
        host = "127.0.0.1", port = 2222, username = "paycore", privateKeyPath = key, hostKeyFingerprints = listOf(fingerprint), inboxDir = "/inbox",
        outboxDir = "/outbox", connectTimeout = connect, ioTimeout = io,
    )

    val validKey = TestAcquirerSftpServer.writePrivateKey(TestAcquirerSftpServer.ecKeyPair())

    Given("AQ-S1 a complete Demo transport entry") {
        val settings = AcquiringTransportSettings(Duration.ofMinutes(2), Duration.ofSeconds(1), mapOf("SIMACQA" to endpoint(validKey)))
        Then("it starts, parses the key once and exposes lease and rename margin (io-timeout + skew) without printing secrets") {
            settings.transmissionLease shouldBe Duration.ofMinutes(2)
            settings.renameSafetyMargin("SIMACQA") shouldBe Duration.ofSeconds(11)
            settings.keyPair("SIMACQA").public.algorithm shouldBe "EC"
            settings.toString() shouldNotContain validKey.toString()
            settings.endpoint("SIMACQA").toString() shouldNotContain fingerprint
        }
        Then("no entry means no transport (NOT_CONFIGURED at use) — and no lease is invented") {
            val none = AcquiringTransportSettings(null, null, null)
            none.endpoint("SIMACQA") shouldBe null
            shouldThrow<IllegalStateException> { none.transmissionLease }
        }
    }

    Given("AQ-S2 review m-1: key files that cannot be used") {
        Then("garbage content fails at startup") {
            val garbage = ownerOnly("garbage").also { Files.writeString(it, "not a key\n") }
            shouldThrow<IllegalArgumentException> { AcquiringTransportSettings(Duration.ofMinutes(2), Duration.ofSeconds(1), mapOf("SIMACQA" to endpoint(garbage))) }
        }
        Then("an encrypted key fails at startup (no passphrase source — Demo keys are unencrypted)") {
            val encrypted = ownerOnly("encrypted").also { f ->
                val ctx = OpenSSHKeyEncryptionContext().apply {
                    password = "test-only-passphrase"
                    cipherName = OpenSSHKeyEncryptionContext.AES
                    cipherMode = "CTR"
                    cipherType = "256"
                }
                Files.newOutputStream(f).use { OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(TestAcquirerSftpServer.ecKeyPair(), "test", ctx, it) }
            }
            shouldThrow<IllegalArgumentException> { AcquiringTransportSettings(Duration.ofMinutes(2), Duration.ofSeconds(1), mapOf("SIMACQA" to endpoint(encrypted))) }
        }
        Then("a key readable by group or others fails at startup") {
            val open = Files.createTempFile("open", ".key").also { f ->
                Files.write(f, Files.readAllBytes(validKey))
                Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"))
            }
            shouldThrow<IllegalArgumentException> { AcquiringTransportSettings(Duration.ofMinutes(2), Duration.ofSeconds(1), mapOf("SIMACQA" to endpoint(open))) }
        }
    }

    Given("AQ-S3 review MJ-1 (c): the lease bound") {
        val e = endpoint(validKey)
        val bound = AcquiringTransportSettings.minimumLease(e, Duration.ofSeconds(1))
        Then("the bound counts two connections (connect + auth + channel open), six requests, the rename margin and the skew twice") {
            bound shouldBe Duration.ofSeconds(2 * (5 + 5 + 10) + 6 * 10 + 10 + 1 + 1)
        }
        Then("a lease at or below the bound, or a missing lease or skew, fails at startup") {
            shouldThrow<IllegalArgumentException> { AcquiringTransportSettings(bound, Duration.ofSeconds(1), mapOf("SIMACQA" to e)) }
            shouldThrow<IllegalArgumentException> { AcquiringTransportSettings(null, Duration.ofSeconds(1), mapOf("SIMACQA" to e)) }
            shouldThrow<IllegalArgumentException> { AcquiringTransportSettings(Duration.ofMinutes(2), null, mapOf("SIMACQA" to e)) }
            AcquiringTransportSettings(bound.plusMillis(1), Duration.ofSeconds(1), mapOf("SIMACQA" to e)).transmissionLease shouldBe bound.plusMillis(1)
        }
    }
})
