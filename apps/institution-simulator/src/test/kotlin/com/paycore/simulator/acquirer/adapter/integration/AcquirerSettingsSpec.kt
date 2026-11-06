package com.paycore.simulator.acquirer.adapter.integration

import com.paycore.simulator.acquirer.domain.AcquirerScenarioAction
import com.paycore.simulator.support.AcquirerTestKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

/**
 * 가상 매입사 설정 fail-closed(ACQUIRING-001 2-E, D-ACQ-006 — DB 없이 설정 객체·SFTP 서버만). 매입사가 없으면 아무것도 열지 않고, 매입사가 있으면 값이 모두
 * 있어야 하며 키는 파일 경로로만 받는다(개인키는 소유자만 읽기). 오류 메시지·toString에 키 원문·경로가 없다.
 */
class AcquirerSettingsSpec : FunSpec({

    val dir: Path = Files.createTempDirectory("acq-settings")
    val hostKey = AcquirerTestKeys.writePrivateKey(dir, "host", AcquirerTestKeys.ecKeyPair())
    val authorized = AcquirerTestKeys.writeAuthorizedKeys(dir, "a.pub", AcquirerTestKeys.ecKeyPair().public)
    val root = Files.createDirectories(dir.resolve("root"))

    fun acquirer(
        code: String = "SIMACQA", username: String = "paycore-a", keys: List<Path> = listOf(authorized), scenarios: List<AcquirerSettings.Scenario>? = null,
    ) = AcquirerSettings.Acquirer(code, username, keys, "pg-demo", scenarios)

    fun settings(
        sftp: AcquirerSettings.Sftp? = AcquirerSettings.Sftp("127.0.0.1", 0, hostKey), rootDir: Path? = root, poll: Duration? = Duration.ofSeconds(2),
        ackDelay: Duration? = Duration.ZERO, resultDelay: Duration? = Duration.ofSeconds(30), partRetention: Duration? = Duration.ofHours(1),
        acquirers: List<AcquirerSettings.Acquirer>? = listOf(acquirer()),
    ) = AcquirerSettings(sftp, rootDir, poll, ackDelay, resultDelay, partRetention, acquirers)

    test("ST-1 without acquirers nothing is configured: the SFTP server does not open a port and the worker scheduler does not start") {
        val none = AcquirerSettings(null, null, null, null, null, null, null)
        none.configured shouldBe false
        val server = AcquirerSftpServer(none)
        server.start()
        server.isRunning shouldBe false
        shouldThrow<IllegalStateException> { server.port }
        settings().configured shouldBe true
    }

    test("ST-2 with acquirers every value is required and well-formed (fail-closed at startup)") {
        val groupReadable = AcquirerTestKeys.writePrivateKey(dir, "group-readable", AcquirerTestKeys.ecKeyPair())
            .also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rw-r-----")) }
        listOf(
            { settings(sftp = null) },
            { settings(rootDir = null) },
            { settings(poll = null) },
            { settings(ackDelay = null) },
            { settings(resultDelay = Duration.ofNanos(1)) },
            { settings(partRetention = null) },
            { settings(partRetention = Duration.ofSeconds(1)) },
            { settings(sftp = AcquirerSettings.Sftp("127.0.0.1", 0, dir.resolve("missing"))) },
            { settings(sftp = AcquirerSettings.Sftp("127.0.0.1", 0, groupReadable)) },
            { settings(sftp = AcquirerSettings.Sftp("127.0.0.1", 70_000, hostKey)) },
            { settings(rootDir = dir.resolve("missing-root")) },
            { settings(acquirers = listOf(acquirer(keys = emptyList()))) },
            { settings(acquirers = listOf(acquirer(keys = listOf(dir.resolve("missing.pub"))))) },
            { settings(acquirers = listOf(acquirer(), acquirer(code = "SIMACQB"))) },
            { settings(acquirers = listOf(acquirer(code = "simacqa"))) },
            {
                settings(acquirers = listOf(acquirer(scenarios = listOf(
                    AcquirerSettings.Scenario(AcquirerScenarioAction.DROP_ACK, null, "t-1", null, null, null, null),
                ))))
            },
        ).forEachIndexed { i, make -> withClue("case $i") { shouldThrow<IllegalArgumentException> { make() } } }
    }

    // 리뷰 m-7·m-6: 허용 공개키 파일·root-dir에 그룹·기타 쓰기 권한이 있으면 기동 실패. 허용 공개키는 매입사마다 모두 합쳐 1~2개(파일 하나에 3개도 거부).
    test("ST-5 group/other-writable root-dir or authorized key file is refused; three authorized keys (paths or keys in one file) are refused") {
        val writableRoot = Files.createDirectories(dir.resolve("writable-root"))
            .also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxrwxr-x")) }
        val writableKey = AcquirerTestKeys.writeAuthorizedKeys(dir, "w.pub", AcquirerTestKeys.ecKeyPair().public)
            .also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rw-rw-r--")) }
        val other = AcquirerTestKeys.writeAuthorizedKeys(dir, "o.pub", AcquirerTestKeys.ecKeyPair().public)
        val third = AcquirerTestKeys.writeAuthorizedKeys(dir, "t.pub", AcquirerTestKeys.ecKeyPair().public)
        shouldThrow<IllegalArgumentException> { settings(rootDir = writableRoot) }
        shouldThrow<IllegalArgumentException> { settings(acquirers = listOf(acquirer(keys = listOf(writableKey)))) }
        shouldThrow<IllegalArgumentException> { settings(acquirers = listOf(acquirer(keys = listOf(authorized, other, third)))) }
        val three = AcquirerTestKeys.writeAuthorizedKeys(dir, "three.pub", *Array(3) { AcquirerTestKeys.ecKeyPair().public })
        shouldThrow<IllegalStateException> { AcquirerSftpServer(settings(acquirers = listOf(acquirer(keys = listOf(three))))).start() }
    }

    test("ST-3 a host key file that is not one OpenSSH private key stops the server start without echoing the file") {
        val garbage = Files.createFile(dir.resolve("garbage"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(garbage, "not-a-key secret-looking-content")
        val failure = shouldThrow<IllegalStateException> { AcquirerSftpServer(settings(sftp = AcquirerSettings.Sftp("127.0.0.1", 0, garbage))).start() }
        failure.message.orEmpty() shouldNotContain "secret-looking-content"
        failure.message.orEmpty() shouldNotContain garbage.toString()
    }

    test("ST-4 settings toString carries no key path or key material") {
        val text = settings().toString()
        text shouldNotContain hostKey.toString()
        text shouldNotContain authorized.toString()
    }
})

