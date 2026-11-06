package com.paycore.simulator.support

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration

/**
 * 가상 매입사 시험 키(D-ACQ-006 "시험은 임시 키쌍" — 시험마다 새 ECDSA P-256, 운영 키 아님. 저장소에 키 파일 없음). 개인키 파일은 소유자만 읽기(rw-------).
 */
object AcquirerTestKeys {
    fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun writePrivateKey(dir: Path, name: String, keyPair: KeyPair): Path {
        val file = Files.createFile(dir.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.newOutputStream(file).use { OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "test-only", null, it) }
        return file
    }

    fun writeAuthorizedKeys(dir: Path, name: String, vararg keys: PublicKey): Path =
        Files.writeString(dir.resolve(name), keys.joinToString("") { PublicKeyEntry.toString(it) + " test-only\n" })
}

/**
 * 시험 전용 SFTP 클라이언트(Apache MINA SSHD 2.20.1 `SshClient`·`SftpClient` — FINANCIAL 운영 클라이언트 코드가 아니다, 앱 간 코드 공유 금지 harness
 * test_simulator_isolation). host key는 기대 공개키와 같아야 접속한다(D-ACQ-006 host key 고정). 접속 하나 = 세션 하나.
 */
class AcquirerSftpTestClient private constructor(private val ssh: SshClient, val session: ClientSession) : Closeable {

    val sftp: SftpClient = SftpClientFactory.instance().createSftpClient(session)

    /** FINANCIAL 2-D(Coordinator 결정 (9) 시도별 임시명)와 같은 절차: `.{id}.dat.{attempt}.part`에 쓰고 `{id}.dat`로 rename. */
    fun upload(finalName: String, content: ByteArray, attempt: Int = 1) {
        write(temp(finalName, attempt), content)
        sftp.rename(temp(finalName, attempt), "/inbox/$finalName")
    }

    fun write(path: String, content: ByteArray) = sftp.write(path).use { it.write(content) }

    fun read(path: String): ByteArray = sftp.read(path).use { it.readAllBytes() }

    /** 디렉터리 목록(readDir은 반복 때 열고 오류를 UncheckedIOException으로 감싼다 — 원래 SFTP 오류로 푼다). */
    fun list(dir: String): List<String> = try {
        sftp.readDir(dir).map { it.filename }.filter { it != "." && it != ".." }.sorted()
    } catch (e: java.io.UncheckedIOException) {
        throw e.cause ?: e
    }

    override fun close() {
        runCatching { sftp.close() }
        runCatching { session.close(true) }
        runCatching { ssh.stop() }
    }

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(10)

        /** 시도별 업로드 임시명(inbox 안 경로). */
        fun temp(finalName: String, attempt: Int) = "/inbox/.$finalName.$attempt.part"

        /** 공개키로 로그인한다(실패하면 예외). */
        fun connect(
            port: Int, username: String, key: KeyPair, hostKey: PublicKey, ciphers: List<org.apache.sshd.common.NamedFactory<org.apache.sshd.common.cipher.Cipher>>? = null,
        ): AcquirerSftpTestClient {
            val ssh = client(hostKey, ciphers)
            try {
                val session = ssh.connect(username, "127.0.0.1", port).verify(TIMEOUT).session
                session.addPublicKeyIdentity(key)
                session.auth().verify(TIMEOUT)
                return AcquirerSftpTestClient(ssh, session)
            } catch (e: Exception) {
                ssh.stop()
                throw e
            }
        }

        /** 비밀번호만으로 로그인을 시도한다(서버는 공개키만 받으므로 실패해야 한다). */
        fun tryPassword(port: Int, username: String, password: String, hostKey: PublicKey): Result<Unit> {
            val ssh = client(hostKey)
            return try {
                runCatching {
                    val session = ssh.connect(username, "127.0.0.1", port).verify(TIMEOUT).session
                    session.use {
                        it.addPasswordIdentity(password)
                        it.auth().verify(TIMEOUT)
                    }
                    Unit
                }
            } finally {
                ssh.stop()
            }
        }

        private fun client(hostKey: PublicKey, ciphers: List<org.apache.sshd.common.NamedFactory<org.apache.sshd.common.cipher.Cipher>>? = null): SshClient =
            SshClient.setUpDefaultClient().apply {
            serverKeyVerifier = ServerKeyVerifier { _, _, key -> KeyUtils.compareKeys(key, hostKey) }
            ciphers?.let { cipherFactories = it }
            start()
        }
    }
}
