package com.paycore.admin.account.adapter.batch

import com.paycore.admin.account.application.provided.IssuedCodeLine
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
 * 코드 출력 대상(§4.9 — logger·표준 출력 일반 쓰기 금지). [prepare]는 DB 변경 **전**에 출력 가능 여부를 확정한다: TTY는 터미널 존재, 파일은
 * 그룹·기타 쓰기 가능 디렉터리면 거부 → `CREATE_NEW` + `NOFOLLOW_LINKS` + POSIX 0600으로 새로 열고(채널을 **커밋 뒤까지 열어 둔다** — S6 리뷰 M-1 TOCTOU) 권한을
 * 다시 읽어 `rw-------`가 아니면(drvfs 등 — §17.8) 지우고 거부. [write]는 커밋 뒤 경로가 여전히 연 그 파일(같은 file key, symlink 아님)일 때만 **같은 채널**에 쓴다
 * (바뀌었으면 쓰지 않고 실패 — 링크 대상은 건드리지 않는다). [discard]는 DB 단계가 실패했을 때 자기가 만든 빈 파일만 지운다.
 */
sealed interface CodeOutput {
    @Throws(IOException::class)
    fun write(lines: List<IssuedCodeLine>)

    fun discard()

    class Tty(private val writer: Writer) : CodeOutput {
        override fun write(lines: List<IssuedCodeLine>) {
            writer.write(render(lines))
            writer.flush()
        }

        override fun discard() = Unit
    }

    class File(private val path: Path, private val channel: FileChannel, private val fileKey: Any?) : CodeOutput {
        override fun write(lines: List<IssuedCodeLine>) {
            channel.use {
                if (!stillOurs()) throw IOException("the output path no longer names the file created for this run")
                val bytes = ByteBuffer.wrap(render(lines).toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) it.write(bytes)
                it.force(true)
            }
        }

        override fun discard() {
            runCatching { channel.close() }
            if (stillOurs()) Files.deleteIfExists(path)
        }

        private fun stillOurs(): Boolean = runCatching {
            val now = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            !now.isSymbolicLink && now.isRegularFile && fileKey != null && now.fileKey() == fileKey
        }.getOrDefault(false)
    }

    sealed interface Prepared {
        data class Ready(val output: CodeOutput) : Prepared
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
                // S7 리뷰 nit-2: 방금 만든 그 파일(file key 일치·symlink 아님)일 때만 지운다.
                if (attributes != null) File(path, channel, attributes.fileKey()).discard()
                return Prepared.Refused("OUTPUT_PERMISSIONS")
            }
            return Prepared.Ready(File(path, channel, attributes.fileKey()))
        }

        fun render(lines: List<IssuedCodeLine>): String = buildString {
            append("# PayCore internal-admin one-time enrollment codes. Deliver each to its owner through a separate channel, then delete this output.\n")
            lines.forEach { append(it.loginName).append('\t').append(it.purpose).append('\t').append(it.code).append('\n') }
        }
    }
}
