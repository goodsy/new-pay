package com.paycore.simulator.acquirer.adapter.integration

import com.paycore.simulator.acquirer.application.required.AcquirerFileArea
import com.paycore.simulator.acquirer.application.required.InboxContent
import com.paycore.simulator.acquirer.application.required.InboxEntry
import com.paycore.simulator.acquirer.application.required.PublishOutcome
import com.paycore.simulator.acquirer.domain.AcquisitionFileRef
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/**
 * [AcquirerFileArea] 로컬 파일 시스템 구현(SFTP chroot와 같은 디렉터리 — `{root-dir}/{매입사}/inbox|outbox`). 링크는 따라가지 않는다(일반 파일만).
 * 게시 = outbox 임시명 `.{name}.tmp` 쓰기 + fsync → 대상 없음만 허용하는 rename(덮어쓰기 없음) → 디렉터리 fsync(리뷰 n-2 — rename 자체의 내구성).
 * 임시 파일 정리의 나이는 파일 시스템 수정 시각과 시스템 시계로 잰다(디스크 위생 — 업무 시각이 아니다).
 */
@Component
class LocalAcquirerFileArea(private val settings: AcquirerSettings) : AcquirerFileArea {

    override fun inboxFiles(acquirerCode: String): List<InboxEntry> {
        val inbox = dir(acquirerCode, INBOX)
        if (!Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.list(inbox).use { s ->
            s.toList().mapNotNull { p ->
                val name = p.fileName.toString()
                if (!NAME.matches(name) || name.startsWith(".")) return@mapNotNull null
                val attrs = runCatching { Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
                attrs?.takeIf { it.isRegularFile }?.let { InboxEntry(name, it.size(), it.lastModifiedTime().toInstant()) }
            }.sortedBy { it.name }
        }
    }

    override fun readInbox(acquirerCode: String, name: String, maxBytes: Int): InboxContent? {
        val file = file(acquirerCode, INBOX, name)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        val digest = MessageDigest.getInstance(SHA_256)
        val kept = ByteArrayOutputStream()
        var size = 0L
        try {
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    if (size + n <= maxBytes) kept.write(buffer, 0, n)
                    size += n
                }
            }
        } catch (e: NoSuchFileException) {
            return null
        }
        val sha = HexFormat.of().formatHex(digest.digest())
        return if (size > maxBytes) InboxContent.TooLarge(sha, size) else InboxContent.Complete(kept.toByteArray(), sha)
    }

    override fun publish(acquirerCode: String, name: String, content: ByteArray): PublishOutcome {
        val target = file(acquirerCode, OUTBOX, name)
        existing(target, content)?.let { return it }
        val temp = file(acquirerCode, OUTBOX, ".$name.tmp")
        FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { ch ->
            val buffer = ByteBuffer.wrap(content)
            while (buffer.hasRemaining()) ch.write(buffer)
            ch.force(true)
        }
        return try {
            Files.move(temp, target)
            syncDirectory(target.parent)
            PublishOutcome.PUBLISHED
        } catch (e: FileAlreadyExistsException) {
            Files.deleteIfExists(temp)
            checkNotNull(existing(target, content))
        }
    }

    override fun sweepTemps(acquirerCode: String, retention: Duration): Int {
        val inbox = dir(acquirerCode, INBOX)
        if (!Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS)) return 0
        val cutoff = Instant.now().minus(retention)
        val stale = Files.list(inbox).use { s ->
            s.toList().filter { p ->
                AcquisitionFileRef.fromTempName(p.fileName.toString()) != null &&
                    runCatching { Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
                        ?.let { it.isRegularFile && it.lastModifiedTime().toInstant().isBefore(cutoff) } == true
            }
        }
        return stale.count { runCatching { Files.deleteIfExists(it) }.getOrDefault(false) }
    }

    private fun existing(target: Path, content: ByteArray): PublishOutcome? {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        val same = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) == content.size.toLong() && Files.readAllBytes(target).contentEquals(content)
        return if (same) PublishOutcome.ALREADY_PRESENT_SAME else PublishOutcome.ALREADY_PRESENT_DIFFERENT
    }

    /** rename을 디스크에 고정한다(POSIX 디렉터리 fsync). 지원하지 않는 파일 시스템이면 건너뛴다(best effort — 다음 회차가 같은 바이트를 확인한다). */
    private fun syncDirectory(dir: Path) {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // 디렉터리 fsync 미지원 — 게시 결과에는 영향 없음.
        }
    }

    private fun dir(acquirerCode: String, area: String): Path {
        require(CODE.matches(acquirerCode)) { "acquirer code outside the grammar" }
        return settings.rootDir.resolve(acquirerCode).resolve(area)
    }

    private fun file(acquirerCode: String, area: String, name: String): Path {
        require(NAME.matches(name) && name != "." && name != "..") { "file name outside the acquirer file name grammar" }
        return dir(acquirerCode, area).resolve(name)
    }

    private companion object {
        const val INBOX = "inbox"
        const val OUTBOX = "outbox"
        const val SHA_256 = "SHA-256"
        const val BUFFER = 64 * 1024
        val CODE = Regex("^[A-Z0-9]{1,32}$")
        val NAME = Regex("^[A-Za-z0-9._-]{1,140}$")
    }
}
