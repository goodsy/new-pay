package com.paycore.simulator.acquirer.adapter.integration

import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.cipher.Cipher
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.mac.Mac
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.signature.Signature
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.server.forward.RejectAllForwardingFilter
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.PublicKey
import java.time.Duration

/**
 * 가상 매입사 내장 SFTP 서버(ACQUIRING-001 2-E §7 — Apache MINA SSHD 2.20.1, catalog `apache-sshd-sftp`, Coordinator 결정 C-1). D-ACQ-006:
 * - 인증은 **공개키만**(비밀번호·keyboard-interactive 없음), 계정마다 허용 공개키 파일(authorized_keys 형식 1~2개). 포워딩·agent·shell·exec 없음(SFTP subsystem만).
 * - host key는 설정 **파일**에서 읽는다(자동 생성 안 함 — 파일이 없거나 읽을 수 없으면 기동 실패). 키 원문·지문을 로그에 남기지 않는다.
 * - 계정 home = `{root}/{매입사}`(sshd VirtualFileSystemFactory — chroot), 그 안 `inbox`·`outbox`를 기동 때 만든다. 파일 연산 규칙은 [AcquirerSftpPolicy].
 * - 알고리즘 allowlist(리뷰 m-1 — FINANCIAL 클라이언트 `SftpAcquirerFileChannel`과 같은 집합): KEX ECDH(NIST P-256/384/521), 암호 AES-GCM/CTR, MAC SHA-2(ETM 우선),
 *   host key 서명 ECDSA·RSA-SHA2(SHA-1·DSA·CBC·arcfour·chacha20 없음 — 두 쪽 목록이 같아야 협상이 결정적이다).
 * - 세션 한도(Demo): idle [IDLE_TIMEOUT], 인증 [AUTH_TIMEOUT], 인증 시도 [MAX_AUTH_REQUESTS]회, 계정당 동시 세션 [MAX_SESSIONS_PER_USER]개.
 * - 허용 공개키는 매입사마다 모두 합쳐 1~2개(교체 중 2개 — 파일 안 키를 센다, 리뷰 m-6).
 * 매입사가 설정되지 않으면 아무것도 열지 않는다(fail-closed). 기동 phase는 기본(권한 기동 검사 뒤).
 */
@Component
class AcquirerSftpServer(private val settings: AcquirerSettings) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var server: SshServer? = null

    /** 실제로 열린 포트(설정 0 = 임의 포트 — 시험). 기동 전·미설정이면 오류. */
    val port: Int get() = checkNotNull(server) { "the acquirer SFTP server is not running" }.port

    override fun start() {
        if (!settings.configured) {
            log.info("virtual acquirer SFTP server not started (no acquirer configured)")
            return
        }
        val hostKey = loadHostKey(settings.sftp.hostKeyPath)
        val keysByUser: Map<String, List<PublicKey>> = settings.accounts.associate { a ->
            val keys = a.authorizedKeyPaths.flatMap(::loadAuthorizedKeys)
            check(keys.size in 1..2) { "acquirer ${a.code}: authorized public keys must total 1..2 (rotation only)" }
            a.username to keys
        }
        val homes = VirtualFileSystemFactory()
        settings.accounts.forEach { a ->
            val home = settings.rootDir.resolve(a.code)
            Files.createDirectories(home.resolve(INBOX))
            Files.createDirectories(home.resolve(OUTBOX))
            homes.setUserHomeDir(a.username, home)
        }
        val uploads = AcquirerSftpUploads()
        val sftp = SftpSubsystemFactory.Builder().withFileSystemAccessor(AcquirerSftpPolicy(uploads)).build()
        sftp.addSftpEventListener(uploads)
        sftp.addSftpEventListener(AcquirerSftpFaults(settings))
        server = SshServer.setUpDefaultServer().apply {
            host = settings.sftp.bindHost
            port = settings.sftp.port
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            keyExchangeFactories = NamedFactory.setUpTransformedFactories(
                false, listOf(BuiltinDHFactories.ecdhp256, BuiltinDHFactories.ecdhp384, BuiltinDHFactories.ecdhp521), ServerBuilder.DH2KEX,
            )
            cipherFactories = listOf<NamedFactory<Cipher>>(BuiltinCiphers.aes256gcm, BuiltinCiphers.aes128gcm, BuiltinCiphers.aes256ctr, BuiltinCiphers.aes128ctr)
            macFactories = listOf<NamedFactory<Mac>>(BuiltinMacs.hmacsha512etm, BuiltinMacs.hmacsha256etm, BuiltinMacs.hmacsha512, BuiltinMacs.hmacsha256)
            signatureFactories = listOf<NamedFactory<Signature>>(
                BuiltinSignatures.nistp256, BuiltinSignatures.nistp384, BuiltinSignatures.nistp521, BuiltinSignatures.rsaSHA512, BuiltinSignatures.rsaSHA256,
            )
            CoreModuleProperties.IDLE_TIMEOUT.set(this, IDLE_TIMEOUT)
            CoreModuleProperties.AUTH_TIMEOUT.set(this, AUTH_TIMEOUT)
            CoreModuleProperties.MAX_AUTH_REQUESTS.set(this, MAX_AUTH_REQUESTS)
            CoreModuleProperties.MAX_CONCURRENT_SESSIONS.set(this, MAX_SESSIONS_PER_USER)
            userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE)
            passwordAuthenticator = null
            keyboardInteractiveAuthenticator = null
            publickeyAuthenticator = PublickeyAuthenticator { username, key, _ -> keysByUser[username]?.any { KeyUtils.compareKeys(it, key) } == true }
            forwardingFilter = RejectAllForwardingFilter.INSTANCE
            agentFactory = null
            shellFactory = null
            commandFactory = null
            fileSystemFactory = homes
            subsystemFactories = listOf(sftp)
            start()
        }
        log.info("virtual acquirer SFTP server started port={} acquirers={}", port, settings.accounts.map { it.code })
    }

    override fun stop() {
        server?.let { runCatching { it.stop(true) } }
        server = null
    }

    override fun isRunning(): Boolean = server?.isStarted == true

    private fun loadHostKey(path: Path): KeyPair = try {
        Files.newInputStream(path).use { input -> SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(HOST_KEY), input, null).toList().single() }
    } catch (e: Exception) {
        // 키 파일 내용·경로를 메시지에 넣지 않는다.
        throw IllegalStateException("acquirer SFTP host key could not be loaded (one unencrypted OpenSSH private key expected)")
    }

    private fun loadAuthorizedKeys(path: Path): List<PublicKey> = try {
        AuthorizedKeyEntry.readAuthorizedKeys(path).map { it.resolvePublicKey(null, PublicKeyEntryResolver.FAILING) }.also { check(it.isNotEmpty()) }
    } catch (e: Exception) {
        throw IllegalStateException("acquirer SFTP authorized public key file could not be loaded (authorized_keys format expected)")
    }

    private companion object {
        const val HOST_KEY = "acquirer-host-key"
        const val INBOX = "inbox"
        const val OUTBOX = "outbox"
        const val MAX_AUTH_REQUESTS = 3
        const val MAX_SESSIONS_PER_USER = 4
        val IDLE_TIMEOUT: Duration = Duration.ofSeconds(60)
        val AUTH_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
