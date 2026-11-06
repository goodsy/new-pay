package com.paycore.financial.acquiring.adapter.integration

import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.Handle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.CopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicInteger

/**
 * 시험 전용 SFTP 서버(Apache MINA SSHD — 운영 코드와 같은 2.20.1). financial-batch 시험이 실제 SFTP 왕복(키 인증·host key 고정·`.part` → rename → stat)을
 * 거치게 한다. 가상 매입사 Simulator(apps/institution-simulator)와 코드를 공유하지 않는다(harness test_simulator_isolation — 계약은 golden 파일로만).
 * 키는 시험마다 새로 만든 임시 ECDSA P-256 키쌍이다(운영 키 아님 — D-ACQ-006 "시험은 임시 키쌍").
 *
 * 장애 주입(결정적): [failNextWrite] = 다음 쓰기 요청에서 세션을 끊는다(업로드 중 끊김), [dropRenameResponses] = rename을 수행한 **뒤** 응답 전에 세션을 끊는다
 * (rename 응답 유실), [rejectNextLogins] = 다음 로그인 n번을 거부한다(재접속 확인 실패).
 *
 * 서버 계약 흉내(명세 rev 0.7 §5.1 — Simulator I-32·C-9, Simulator 코드 공유 없음): 쓰기 열기는 inbox `.{fileId}.dat.{n}.part`(n = `[1-9][0-9]{0,5}`)만, Append 금지,
 * 옛 공용 임시명 `.{fileId}.dat.part`·그 밖 이름은 PERMISSION_DENIED. rename은 그 임시명 → 짝 최종명만, 쓰기 핸들이 열린 임시명 rename은 PERMISSION_DENIED,
 * 대상이 있으면 실패(덮어쓰기 없음). [deletePartBeforeRename] = 다음 rename 직전 임시 파일을 지운다(part-retention 정리 — NO_SUCH_FILE).
 */
class TestAcquirerSftpServer : AutoCloseable {
    /** 쓰기로 연 inbox 이름(순서대로 — 시도별 임시명 검사용). */
    val writeOpenedNames: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private val openWrites: MutableSet<Path> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 다음 rename n번은 직전에 임시 파일을 지운다(서버 정리와 경합 — rename NO_SUCH_FILE). */
    val deletePartBeforeRename = AtomicInteger()
    val root: Path = Files.createTempDirectory("acq-sftp-root")
    val inbox: Path = Files.createDirectories(root.resolve("inbox"))
    val outbox: Path = Files.createDirectories(root.resolve("outbox"))
    val hostKey: KeyPair = ecKeyPair()
    val otherHostKey: KeyPair = ecKeyPair()
    val clientKey: KeyPair = ecKeyPair()
    val clientKeyFile: Path = writePrivateKey(clientKey)
    val hostKeyFingerprint: String = KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey.public)
    val otherFingerprint: String = KeyUtils.getFingerPrint(BuiltinDigests.sha256, otherHostKey.public)

    @Volatile var failNextWrite = false
    val dropRenameResponses = AtomicInteger()
    val rejectNextLogins = AtomicInteger()

    /** rename 응답을 끊을 때 그 뒤 로그인 n번을 거부한다(재접속 확인 실패 — 첫 접속은 정상). */
    val rejectLoginsAfterDroppedRename = AtomicInteger()

    /** 다음 rename n번을 수행하지 않고 실패 상태로 답한다(명시적 거절 — 세션 유지). */
    val refuseNextRenames = AtomicInteger()

    /** 다음 rename n번을 수행하지 않고 응답 전에 세션을 끊는다(rename 미수행 + 응답 유실 — 리뷰 MJ-2). */
    val dropRenameBeforeMove = AtomicInteger()

    /** 다음 rename n번은 성공 응답을 주지만 최종명을 곧바로 지운다(rename 뒤 없음 — RENAMED_BUT_ABSENT). */
    val removeAfterRename = AtomicInteger()

    /** 다음 rename이 성공하면(응답은 정상) 그 뒤 로그인 n번을 거부한다(재접속 확인 실패 — Delivered(RENAMED)). */
    val rejectLoginsAfterRename = AtomicInteger()
    val writeRequests = AtomicInteger()
    val renames = AtomicInteger()
    val logins = AtomicInteger()

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(hostKey)
        passwordAuthenticator = null
        keyboardInteractiveAuthenticator = null
        publickeyAuthenticator = PublickeyAuthenticator { username, key, _ ->
            if (rejectNextLogins.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) return@PublickeyAuthenticator false
            val ok = username == USER && KeyUtils.compareKeys(key, clientKey.public)
            if (ok) logins.incrementAndGet()
            ok
        }
        fileSystemFactory = VirtualFileSystemFactory(root)
        subsystemFactories = listOf(
            SftpSubsystemFactory.Builder().build().also { it.addSftpEventListener(FaultInjection()) },
        )
        start()
    }

    val port: Int get() = server.port

    private inner class FaultInjection : SftpEventListener {
        override fun writing(
            session: ServerSession, remoteHandle: String, localHandle: FileHandle, offset: Long, data: ByteArray, dataOffset: Int, dataLen: Int,
        ) {
            writeRequests.incrementAndGet()
            if (failNextWrite) {
                failNextWrite = false
                session.close(true)
                throw IOException("injected: connection lost during upload")
            }
        }

        override fun opening(session: ServerSession, remoteHandle: String, localHandle: Handle) {
            val h = localHandle as? FileHandle ?: return
            val options = h.openOptions
            if (StandardOpenOption.WRITE !in options && StandardOpenOption.APPEND !in options) return
            val name = h.file.fileName.toString()
            writeOpenedNames.add(name)
            if (StandardOpenOption.APPEND in options || h.file.parent?.fileName?.toString() != "inbox" || TEMP.matchEntire(name) == null) {
                throw AccessDeniedException(name)
            }
        }

        override fun open(session: ServerSession, remoteHandle: String, localHandle: Handle) {
            val h = localHandle as? FileHandle ?: return
            if (StandardOpenOption.WRITE in h.openOptions) openWrites.add(h.file.toAbsolutePath())
        }

        override fun closed(session: ServerSession, remoteHandle: String, localHandle: Handle, thrown: Throwable?) {
            (localHandle as? FileHandle)?.let { openWrites.remove(it.file.toAbsolutePath()) }
        }

        override fun moving(session: ServerSession, srcPath: Path, dstPath: Path, opts: MutableCollection<CopyOption>) {
            val m = TEMP.matchEntire(srcPath.fileName.toString())
            if (m == null || dstPath.fileName.toString() != "${m.groupValues[1]}.dat" || dstPath.parent != srcPath.parent) {
                throw AccessDeniedException(srcPath.fileName.toString())
            }
            if (srcPath.toAbsolutePath() in openWrites) throw AccessDeniedException(srcPath.fileName.toString())
            if (deletePartBeforeRename.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) Files.deleteIfExists(srcPath)
            if (refuseNextRenames.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw IOException("injected: rename refused")
            if (dropRenameBeforeMove.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                session.close(true)
                throw IOException("injected: connection lost before the rename was performed")
            }
        }

        override fun moved(session: ServerSession, srcPath: Path, dstPath: Path, opts: MutableCollection<CopyOption>, thrown: Throwable?) {
            if (thrown != null) return
            renames.incrementAndGet()
            if (removeAfterRename.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) Files.deleteIfExists(dstPath)
            rejectNextLogins.addAndGet(rejectLoginsAfterRename.getAndSet(0))
            if (dropRenameResponses.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                rejectNextLogins.addAndGet(rejectLoginsAfterDroppedRename.getAndSet(0))
                session.close(true)
            }
        }
    }

    fun inboxFile(name: String): Path = inbox.resolve(name)

    override fun close() {
        server.stop(true)
    }

    companion object {
        const val USER = "paycore"

        /** 시도별 업로드 임시명(서버 계약 rev 0.7): 그룹 1 = 파일 ID, 그룹 2 = 시도 번호 n. */
        val TEMP = Regex("^\\.(ACQ\\.[A-Z0-9]{1,32}\\.(?:TEST|LIVE)\\.[0-9]{8}\\.[0-9]{3})\\.dat\\.([1-9][0-9]{0,5})\\.part$")

        fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        fun writePrivateKey(keyPair: KeyPair): Path {
            val file = Files.createTempFile("acq-client-key", ".pem", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.newOutputStream(file).use { OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "test-only", null, it) }
            return file
        }
    }
}

/**
 * 시험 SFTP 서버와 매입사 접속 설정. 매입사 [ACQUIRERS]는 같은 서버의 정상 설정, [WRONG_HOST_KEY_ACQUIRER]는 다른 host key 지문을 고정한 설정이다.
 * 설정 이름은 운영과 같다(paycore.financial.acquiring.transport.*). lease는 Demo 값.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestAcquirerSftpConfig {

    @Bean(destroyMethod = "close")
    fun testAcquirerSftpServer(): TestAcquirerSftpServer = TestAcquirerSftpServer()

    @Bean
    fun testAcquirerTransportProperties(server: TestAcquirerSftpServer): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
        val p = "paycore.financial.acquiring.transport"
        registry.add("$p.lease") { "PT2M" }
        registry.add("$p.clock-skew-allowance") { "PT1S" }
        (ACQUIRERS + RESPONSE_ACQUIRERS + VIEW_ACQUIRERS + WRONG_HOST_KEY_ACQUIRER).forEach { code ->
            val a = "$p.acquirers.$code"
            registry.add("$a.host") { "127.0.0.1" }
            registry.add("$a.port") { server.port }
            registry.add("$a.username") { TestAcquirerSftpServer.USER }
            registry.add("$a.private-key-path") { server.clientKeyFile.toString() }
            registry.add("$a.host-key-fingerprints[0]") { if (code == WRONG_HOST_KEY_ACQUIRER) server.otherFingerprint else server.hostKeyFingerprint }
            registry.add("$a.inbox-dir") { "/inbox" }
            registry.add("$a.outbox-dir") { "/outbox" }
            registry.add("$a.connect-timeout") { "PT5S" }
            registry.add("$a.io-timeout") { "PT10S" }
        }
    }

    companion object {
        val ACQUIRERS = (1..24).map { "SFTPT$it" }

        /** 2-F 응답·마감·CANCEL·스케줄러 시험 lane(같은 컨텍스트·같은 DB를 쓰는 전송 시험 lane과 겹치지 않게 따로 둔다). */
        val RESPONSE_ACQUIRERS = (1..40).map { "SFTPR$it" }

        /** ACQ-VIEW-001 매입 현황 조회 시험 lane(같은 컨텍스트·같은 DB의 다른 시험 lane과 겹치지 않게 따로 둔다). */
        val VIEW_ACQUIRERS = (1..20).map { "SFTPV$it" }
        const val WRONG_HOST_KEY_ACQUIRER = "SFTPBADHOST"
    }
}
