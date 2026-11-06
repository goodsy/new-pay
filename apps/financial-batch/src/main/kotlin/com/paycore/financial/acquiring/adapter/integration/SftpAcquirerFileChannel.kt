package com.paycore.financial.acquiring.adapter.integration

import com.paycore.financial.acquiring.application.required.AcquirerChannelFailure
import com.paycore.financial.acquiring.application.required.AcquirerFileChannel
import com.paycore.financial.acquiring.application.required.AcquirerFileSession
import com.paycore.financial.acquiring.application.required.AcquirerRemoteFile
import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.cipher.Cipher
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.mac.Mac
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.signature.Signature
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.sftp.SftpModuleProperties
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.io.IOException
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap

/**
 * [AcquirerFileChannel] SFTP 구현(Apache MINA SSHD 2.20.1 — Coordinator 결정 C-1, spring-integration-sftp 미사용). D-ACQ-006:
 * - 클라이언트 인증은 **공개키만**(비밀번호·keyboard-interactive 없음), 개인키는 설정 파일 경로에서 읽는다(원문을 로그·예외에 넣지 않는다).
 * - 서버 host key는 설정 지문(SHA-256) 목록과 같아야 한다. 다르면 KEX에서 거부하고 [AcquirerChannelFailure.HostKeyRejected](재시도 안 함 — 호출자가 경보).
 * - 매입사마다 SshClient 하나(그 매입사 지문만 아는 verifier). 처음 쓸 때 만들고 Bean 종료 때 멈춘다.
 * 경로는 설정된 inbox/outbox 안의 이름만 받는다(이름 문법 밖 — 구분자·`..` — 은 [IllegalArgumentException]).
 */
@Component
class SftpAcquirerFileChannel(private val settings: AcquiringTransportSettings) : AcquirerFileChannel, DisposableBean {

    private class Client(val ssh: SshClient, val keyPair: KeyPair, val rejected: MutableSet<ClientSession>)

    private val clients = ConcurrentHashMap<String, Client>()

    override fun isConfigured(acquirerCode: String): Boolean = settings.endpoint(acquirerCode) != null

    override fun open(acquirerCode: String): AcquirerFileSession {
        val endpoint = settings.endpoint(acquirerCode) ?: throw AcquirerChannelFailure.NotConfigured(acquirerCode)
        val client = clients.computeIfAbsent(acquirerCode) { start(endpoint, settings.keyPair(acquirerCode)) }
        val future = client.ssh.connect(endpoint.username, endpoint.host, endpoint.port)
        val session = try {
            future.verify(endpoint.connectTimeout).session
        } catch (e: IOException) {
            // 리뷰 NIT: 시간 초과로 끝난 연결 시도가 뒤에서 세션을 만들지 않게 취소한다.
            future.cancel()
            throw AcquirerChannelFailure.ConnectFailed(null)
        }
        try {
            session.addPublicKeyIdentity(client.keyPair)
            session.auth().verify(endpoint.connectTimeout)
        } catch (e: IOException) {
            val hostKeyRejected = client.rejected.remove(session)
            val stillOpen = session.isOpen
            session.close(true)
            when {
                hostKeyRejected -> throw AcquirerChannelFailure.HostKeyRejected()
                stillOpen -> throw AcquirerChannelFailure.AuthenticationFailed(null)
                else -> throw AcquirerChannelFailure.ConnectFailed(null)
            }
        }
        val sftp = try {
            SftpClientFactory.instance().createSftpClient(session)
        } catch (e: IOException) {
            session.close(true)
            throw AcquirerChannelFailure.Interrupted(null)
        }
        return SftpSession(endpoint, session, sftp)
    }

    /**
     * 리뷰 m-2: 클라이언트 구성을 명시한다 — `~/.ssh/config`(HostConfigEntryResolver)·기본 키 위치(KeyIdentityProvider)를 쓰지 않고 설정 키 하나만 쓴다.
     * 알고리즘 allowlist: KEX ECDH(NIST P-256/384/521), 암호 AES-GCM/CTR, MAC SHA-2(ETM 우선), host key 서명 ECDSA·RSA-SHA2(SHA-1·DSA·CBC·arcfour 없음).
     */
    private fun start(endpoint: AcquiringTransportSettings.Endpoint, keyPair: KeyPair): Client {
        val allowed = endpoint.hostKeyFingerprints.toSet()
        val rejected = ConcurrentHashMap.newKeySet<ClientSession>()
        val ssh = SshClient.setUpDefaultClient().apply {
            hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
            keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
            keyExchangeFactories = NamedFactory.setUpTransformedFactories(
                false, listOf(BuiltinDHFactories.ecdhp256, BuiltinDHFactories.ecdhp384, BuiltinDHFactories.ecdhp521), ClientBuilder.DH2KEX,
            )
            cipherFactories = listOf<NamedFactory<Cipher>>(BuiltinCiphers.aes256gcm, BuiltinCiphers.aes128gcm, BuiltinCiphers.aes256ctr, BuiltinCiphers.aes128ctr)
            macFactories = listOf<NamedFactory<Mac>>(BuiltinMacs.hmacsha512etm, BuiltinMacs.hmacsha256etm, BuiltinMacs.hmacsha512, BuiltinMacs.hmacsha256)
            signatureFactories = listOf<NamedFactory<Signature>>(
                BuiltinSignatures.nistp256, BuiltinSignatures.nistp384, BuiltinSignatures.nistp521, BuiltinSignatures.rsaSHA512, BuiltinSignatures.rsaSHA256,
            )
            userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE)
            serverKeyVerifier = ServerKeyVerifier { session, _, key ->
                val ok = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key) in allowed
                if (!ok) rejected.add(session)
                ok
            }
            CoreModuleProperties.IDLE_TIMEOUT.set(this, endpoint.ioTimeout)
            CoreModuleProperties.NIO2_READ_TIMEOUT.set(this, endpoint.ioTimeout)
            SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT.set(this, endpoint.ioTimeout)
            start()
        }
        return Client(ssh, keyPair, rejected)
    }

    override fun destroy() {
        clients.values.forEach { runCatching { it.ssh.stop() } }
        clients.clear()
    }

    private class SftpSession(
        private val endpoint: AcquiringTransportSettings.Endpoint,
        private val session: ClientSession,
        private val sftp: SftpClient,
    ) : AcquirerFileSession {

        override fun inboxSize(name: String): Long? = op {
            try {
                sftp.stat(path(endpoint.inboxDir, name)).size
            } catch (e: SftpException) {
                if (e.status == SftpConstants.SSH_FX_NO_SUCH_FILE) null else throw e
            }
        }

        override fun readInbox(name: String): ByteArray = op { read(path(endpoint.inboxDir, name)) }

        /**
         * Write·Create·Truncate로 열고(Append 금지 — 서버 계약 rev 0.7 §5.1) 쓴 뒤 **반환 전에 핸들을 닫는다**(`use` — 닫기 응답까지 기다림). 따라서 이어지는
         * [renameInbox]는 항상 닫힌 파일을 옮긴다(열린 쓰기 핸들 rename은 서버가 PERMISSION_DENIED). 요청 크기는 클라이언트 기본 버퍼(32 KiB 이하)라
         * offset + 길이 ≤ 봉인 바이트 상한(16 MiB − 1)이다.
         */
        override fun writeInbox(name: String, content: ByteArray): Unit = op {
            sftp.write(path(endpoint.inboxDir, name), SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate).use { it.write(content) }
        }

        override fun renameInbox(from: String, to: String): Unit = op { sftp.rename(path(endpoint.inboxDir, from), path(endpoint.inboxDir, to)) }

        override fun listOutbox(): List<AcquirerRemoteFile> = op {
            // readDir는 반복하면서 디렉터리를 연다 — 반복 중 SFTP 오류는 UncheckedIOException으로 감싸져 나오므로 원인으로 푼다(op가 분류).
            try {
                sftp.readDir(endpoint.outboxDir).filter { NAME.matches(it.filename) && it.filename != "." && it.filename != ".." && it.attributes.isRegularFile }
                    .map { AcquirerRemoteFile(it.filename, it.attributes.size) }.sortedBy { it.name }
            } catch (e: java.io.UncheckedIOException) {
                throw e.cause ?: IOException("outbox listing failed")
            }
        }

        override fun readOutbox(name: String): ByteArray = op { read(path(endpoint.outboxDir, name)) }

        override fun close() {
            runCatching { sftp.close() }
            runCatching { session.close(false).await(endpoint.ioTimeout) }
        }

        private fun read(path: String): ByteArray = sftp.read(path).use { input ->
            val bytes = input.readNBytes(MAX_FILE_BYTES + 1)
            if (bytes.size > MAX_FILE_BYTES) throw AcquirerChannelFailure.Refused(SftpConstants.SSH_FX_FAILURE)
            bytes
        }

        private fun <T> op(block: () -> T): T = try {
            block()
        } catch (e: AcquirerChannelFailure) {
            throw e
        } catch (e: SftpException) {
            throw AcquirerChannelFailure.Refused(e.status)
        } catch (e: IOException) {
            throw AcquirerChannelFailure.Interrupted(null)
        }

        private fun path(dir: String, name: String): String {
            require(NAME.matches(name) && name != "." && name != "..") { "file name outside the acquirer file name grammar" }
            return "${dir.trimEnd('/')}/$name"
        }
    }

    private companion object {
        /** 봉인 바이트 상한(MEDIUMBLOB 16 MiB − 1 — I-11). */
        const val MAX_FILE_BYTES = 16 * 1024 * 1024 - 1
        val NAME = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}
