package com.paycore.payment.payment.adapter.batch

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/**
 * `demo-credential` 출력(DEMO-SEED-001 명세 §4.7, I-15 — DB 없음): 0600·`CREATE_NEW`·공유 디렉터리·기존 파일·symlink 거부, 쓰기 실패 시 자기가 만든 파일 삭제(m4 —
 * 일부만 쓰인 키 원문이 남지 않게).
 */
class DemoCredentialOutputSpec : FunSpec({

    val noTerminal = ConsoleAccess { null }

    fun privateDir() = Files.createTempDirectory("demo-credential-out-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))

    test("a new file is created owner-only (0600) and receives the header and lines") {
        val path = privateDir().resolve("k.tsv")
        val ready = DemoCredentialOutput.prepare("file:$path", noTerminal).shouldBeInstanceOf<DemoCredentialOutput.Prepared.Ready>()
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path)) shouldBe "rw-------"
        ready.output.write("h", listOf("a" to "1", "b" to "2"))
        Files.readString(path) shouldBe "# h\na\t1\nb\t2\n"
    }

    test("existing file, symlink, shared directory, missing terminal and malformed spec are refused") {
        val dir = privateDir()
        val existing = Files.createFile(dir.resolve("e.tsv"))
        DemoCredentialOutput.prepare("file:$existing", noTerminal) shouldBe DemoCredentialOutput.Prepared.Refused("OUTPUT_FILE_EXISTS")
        val link = Files.createSymbolicLink(dir.resolve("l.tsv"), dir.resolve("target.tsv"))
        DemoCredentialOutput.prepare("file:$link", noTerminal).shouldBeInstanceOf<DemoCredentialOutput.Prepared.Refused>()
        Files.exists(dir.resolve("target.tsv")) shouldBe false
        val shared = Files.createTempDirectory("demo-credential-shared-").also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxrwxrwx")) }
        DemoCredentialOutput.prepare("file:${shared.resolve("s.tsv")}", noTerminal) shouldBe DemoCredentialOutput.Prepared.Refused("OUTPUT_DIRECTORY_SHARED")
        DemoCredentialOutput.prepare("tty", noTerminal) shouldBe DemoCredentialOutput.Prepared.Refused("OUTPUT_NO_TERMINAL")
        DemoCredentialOutput.prepare("file:", noTerminal) shouldBe DemoCredentialOutput.Prepared.Invalid
        DemoCredentialOutput.prepare(null, noTerminal) shouldBe DemoCredentialOutput.Prepared.Invalid
    }

    test("a write that fails after commit deletes the file it created (m4)") {
        val path = Files.createFile(privateDir().resolve("broken.tsv"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        val channel = FileChannel.open(path, StandardOpenOption.WRITE)
        val key = Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()
        channel.close()
        val output = DemoCredentialOutput.File(path, channel, key)
        runCatching { output.write("h", listOf("apiKey" to "pc_test_sk_x.y")) }.isFailure shouldBe true
        Files.exists(path) shouldBe false
    }
})
