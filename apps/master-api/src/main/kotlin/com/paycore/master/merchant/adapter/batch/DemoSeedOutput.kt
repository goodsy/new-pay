package com.paycore.master.merchant.adapter.batch

import java.io.IOException
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * `demo-seed` 출력 대상(명세 §4.7 — internal-admin `CodeOutput`과 같은 규칙, 앱 간 production 코드 공유 없음). [prepare]는 DB 변경 **전**에 출력 가능
 * 여부를 확정한다: TTY는 터미널 존재, 파일은 그룹·기타 쓰기 가능 디렉터리 거부 → `CREATE_NEW` + `NOFOLLOW_LINKS` + 0600으로 열고 권한을 다시 읽어
 * `rw-------`가 아니면 지우고 거부. [write]는 커밋 뒤 경로가 여전히 연 그 파일일 때만 같은 채널에 쓰고, **쓰기 실패면 자기가 만든 그 파일을 지운다**(m4).
 * logger·표준 출력에는 쓰지 않는다.
 */
sealed interface DemoSeedOutput {
    @Throws(IOException::class)
    fun write(header: String, lines: List<Pair<String, String>>)

    fun discard()

    class Tty(private val writer: Writer) : DemoSeedOutput {
        override fun write(header: String, lines: List<Pair<String, String>>) {
            writer.write(render(header, lines))
            writer.flush()
        }

        override fun discard() = Unit
    }

    class File(private val path: Path, private val channel: FileChannel, private val fileKey: Any?) : DemoSeedOutput {
        override fun write(header: String, lines: List<Pair<String, String>>) {
            try {
                channel.use {
                    if (!stillOurs()) throw IOException("the output path no longer names the file created for this run")
                    val bytes = ByteBuffer.wrap(render(header, lines).toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) it.write(bytes)
                    it.force(true)
                }
            } catch (e: IOException) {
                deleteIfOurs()
                throw e
            }
        }

        override fun discard() {
            runCatching { channel.close() }
            deleteIfOurs()
        }

        private fun deleteIfOurs() {
            if (stillOurs()) runCatching { Files.deleteIfExists(path) }
        }

        private fun stillOurs(): Boolean = runCatching {
            val now = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            !now.isSymbolicLink && now.isRegularFile && fileKey != null && now.fileKey() == fileKey
        }.getOrDefault(false)
    }

    sealed interface Prepared {
        data class Ready(val output: DemoSeedOutput) : Prepared
        data class Refused(val reason: String) : Prepared
        data object Invalid : Prepared
    }

    companion object {
        private const val OWNER_ONLY = "rw-------"
        private const val FILE_PREFIX = "file:"
        private val SHARED_WRITE = setOf(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)

        fun prepare(spec: String?, console: ConsoleAccess): Prepared = when {
            spec == "tty" -> console.terminalWriter()?.let { Prepared.Ready(Tty(it)) } ?: Prepared.Refused("OUTPUT_NO_TERMINAL")
            spec != null && spec.startsWith(FILE_PREFIX) && spec.length > FILE_PREFIX.length -> createOwnerOnly(Path.of(spec.removePrefix(FILE_PREFIX)).toAbsolutePath())
            else -> Prepared.Invalid
        }

        private fun createOwnerOnly(path: Path): Prepared {
            val parent = path.parent ?: return Prepared.Refused("OUTPUT_UNWRITABLE")
            val parentPermissions = runCatching { Files.getPosixFilePermissions(parent) }.getOrElse { return Prepared.Refused("OUTPUT_NOT_POSIX") }
            if (parentPermissions.any { it in SHARED_WRITE }) return Prepared.Refused("OUTPUT_DIRECTORY_SHARED")
            val channel = try {
                FileChannel.open(
                    path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)),
                )
            } catch (_: FileAlreadyExistsException) {
                return Prepared.Refused("OUTPUT_FILE_EXISTS")
            } catch (_: UnsupportedOperationException) {
                return Prepared.Refused("OUTPUT_NOT_POSIX")
            } catch (_: IOException) {
                return Prepared.Refused("OUTPUT_UNWRITABLE")
            }
            val attributes = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            val actual = runCatching { PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)) }.getOrNull()
            if (attributes == null || actual != OWNER_ONLY) {
                runCatching { channel.close() }
                if (attributes != null) File(path, channel, attributes.fileKey()).discard()
                return Prepared.Refused("OUTPUT_PERMISSIONS")
            }
            return Prepared.Ready(File(path, channel, attributes.fileKey()))
        }

        fun render(header: String, lines: List<Pair<String, String>>): String = buildString {
            append("# ").append(header).append('\n')
            lines.forEach { (k, v) -> append(k).append('\t').append(v).append('\n') }
        }
    }
}
