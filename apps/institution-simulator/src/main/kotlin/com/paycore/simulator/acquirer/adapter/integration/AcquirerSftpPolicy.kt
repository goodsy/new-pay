package com.paycore.simulator.acquirer.adapter.integration

import com.paycore.simulator.acquirer.domain.AcquisitionFileRef
import org.apache.sshd.sftp.server.DirectoryHandle
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.security.Principal
import org.slf4j.LoggerFactory

/**
 * 가상 매입사 SFTP 접근 규칙(ACQUIRING-001 2-E §7 — `SftpFileSystemAccessor`로 모든 파일 연산을 거른다). chroot(계정 home = `{root}/{매입사}`) 안에서도:
 * - 쓰기 열기는 inbox의 시도별 업로드 임시명 `.{fileId}.dat.{n}.part`(n = 1~999999, Coordinator 결정 (9))만. 옛 공용 임시명 `.{id}.dat.part`·최종명·outbox·
 *   다른 위치 쓰기는 거부(PERMISSION_DENIED). 새 임시 파일은 inbox에 이미 [MAX_TEMPS]개 있으면 먼저 같은 파일 ID의 닫힌 이전 시도 임시 파일을 지우고(재리뷰 m-6),
 *   그래도 상한이면 거부(디스크 상한 — 오래된 것은 Worker가 정리, 리뷰 m-1). 쓰기 채널은 [BoundedUploadChannel]로 감싸 16 MiB − 1을 넘는 쓰기를 거부한다(재리뷰 m-2).
 * - rename은 시도별 임시명 → 그 짝 `/inbox/{id}.dat`만, 대상이 있으면 거부(덮어쓰기 금지 — 같은 파일 ID 최종 파일은 한 번만 생긴다), 쓰기 핸들이 아직 열려 있으면
 *   거부(리뷰 m-2). 거부는 warn 로그(파일 ID만 — 리뷰 m-3).
 * - 읽기는 inbox 안 파일(FINANCIAL 되읽기 확인 — I-24)과 outbox 안 파일. 점으로 시작하는 outbox 이름(게시 중 임시 파일)은 읽기 거부, 받는 쪽은 무시한다(리뷰 n-1).
 *   목록은 `/inbox`·`/outbox`만.
 * - 삭제·디렉터리 생성·링크·복사·속성 변경(SETSTAT·chmod·chown)은 모두 거부(받은 파일 보존 — FINANCIAL 전송 확인 근거).
 * sshd 2.20.1 `FileHandle`은 [openFile]에 null이 아닌 열기 옵션 집합과 속성 배열(없으면 빈 배열)을 넘긴다(바이트코드 확인 — 리뷰 n-4). 경로의 파일 이름만 null일 수
 * 있다(chroot 루트) — [deny]가 다룬다.
 * 경로 이탈(`..`·절대 경로)은 sshd RootedFileSystem이 chroot 안으로 가두고, 이 규칙이 다시 정확히 두 단계(`/inbox|outbox/이름`)만 허용한다.
 */
class AcquirerSftpPolicy(private val uploads: AcquirerSftpUploads) : SftpFileSystemAccessor {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun openFile(
        subsystem: SftpSubsystemProxy, fileHandle: FileHandle, file: Path, handle: String, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel {
        val loc = locate(file) ?: deny(file)
        val writing = options.any { it in WRITE_OPTIONS }
        val allowed = if (writing) {
            options.none { it in FORBIDDEN_WRITE_OPTIONS } && loc.dir == INBOX && AcquisitionFileRef.fromTempName(loc.name) != null
        } else {
            loc.dir == INBOX || !loc.name.startsWith(".")
        }
        if (!allowed) deny(file)
        if (writing && !Files.exists(file, LinkOption.NOFOLLOW_LINKS) && tempsIn(file.parent) >= MAX_TEMPS) {
            // 재리뷰 m-6: 상한이면 같은 파일 ID의 닫힌(쓰기 핸들 없는) 이전 시도 임시 파일을 먼저 지운다 — FINANCIAL 재시도가 자기 잔여물에 막히지 않게.
            clearClosedAttempts(subsystem.serverSession.username, file)
            if (tempsIn(file.parent) >= MAX_TEMPS) deny(file)
        }
        val channel = super.openFile(subsystem, fileHandle, file, handle, options, *attrs)
        // 재리뷰 m-2: 크기 상한을 채널에서 지킨다(copy-data 등 쓰기 이벤트를 거치지 않는 서버 안 쓰기 포함).
        return if (writing) BoundedUploadChannel(channel, AcquirerSftpUploads.MAX_FILE_BYTES) else channel
    }

    override fun openDirectory(
        subsystem: SftpSubsystemProxy, dirHandle: DirectoryHandle, dir: Path, handle: String, vararg linkOptions: LinkOption,
    ): DirectoryStream<Path> {
        val normalized = dir.toAbsolutePath().normalize()
        if (normalized.nameCount != 1 || normalized.getName(0).toString() !in DIRS) deny(dir)
        return super.openDirectory(subsystem, dirHandle, dir, handle, *linkOptions)
    }

    override fun renameFile(subsystem: SftpSubsystemProxy, oldPath: Path, newPath: Path, opts: MutableCollection<CopyOption>) {
        val from = locate(oldPath) ?: refuseRename(oldPath, null)
        val to = locate(newPath) ?: refuseRename(newPath, from.name)
        if (from.dir != INBOX || to.dir != INBOX || AcquisitionFileRef.finalNameOfTemp(from.name) != to.name) refuseRename(newPath, from.name)
        if (uploads.hasOpenWriter(subsystem.serverSession.username, oldPath)) refuseRename(oldPath, from.name)
        if (Files.exists(newPath, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("acquirer SFTP rename refused (final name exists) fileId={}", AcquisitionFileRef.fromTempName(from.name)?.value)
            throw FileAlreadyExistsException(to.name)
        }
        // 덮어쓰기 옵션(REPLACE_EXISTING·ATOMIC_MOVE — POSIX rename은 대상을 바꿔치운다)을 버리고 대상 없음만 허용하는 move로 옮긴다.
        super.renameFile(subsystem, oldPath, newPath, mutableListOf())
    }

    override fun removeFile(subsystem: SftpSubsystemProxy, path: Path, isDirectory: Boolean): Unit = deny(path)

    override fun createDirectory(subsystem: SftpSubsystemProxy, path: Path): Unit = deny(path)

    override fun createLink(subsystem: SftpSubsystemProxy, link: Path, existing: Path, symLink: Boolean): Unit = deny(link)

    override fun copyFile(subsystem: SftpSubsystemProxy, src: Path, dst: Path, opts: MutableCollection<CopyOption>): Unit = deny(dst)

    override fun setFileAttribute(subsystem: SftpSubsystemProxy, file: Path, view: String, attribute: String, value: Any?, vararg options: LinkOption): Unit =
        deny(file)

    override fun setFileOwner(subsystem: SftpSubsystemProxy, file: Path, value: Principal, vararg options: LinkOption): Unit = deny(file)

    override fun setGroupOwner(subsystem: SftpSubsystemProxy, file: Path, value: Principal, vararg options: LinkOption): Unit = deny(file)

    override fun setFilePermissions(subsystem: SftpSubsystemProxy, file: Path, perms: MutableSet<PosixFilePermission>, vararg options: LinkOption): Unit =
        deny(file)

    override fun setFileAccessControl(subsystem: SftpSubsystemProxy, file: Path, acl: MutableList<AclEntry>, vararg options: LinkOption): Unit = deny(file)

    private data class Location(val dir: String, val name: String)

    /** chroot 안 `/inbox/이름`·`/outbox/이름`(정확히 두 단계)만 위치로 인정한다. */
    private fun locate(path: Path): Location? {
        val p = path.toAbsolutePath().normalize()
        if (p.nameCount != 2) return null
        val dir = p.getName(0).toString()
        val name = p.getName(1).toString()
        return if (dir in DIRS && NAME.matches(name)) Location(dir, name) else null
    }

    private fun deny(path: Path): Nothing = throw AccessDeniedException(path.fileName?.toString() ?: "/")

    /** rename 거부 — warn 로그는 파일 ID만(이름 문법 밖이면 없음). */
    private fun refuseRename(path: Path, fromName: String?): Nothing {
        log.warn("acquirer SFTP rename refused fileId={}", fromName?.let { AcquisitionFileRef.fromTempName(it)?.value })
        deny(path)
    }

    /** [file]과 같은 파일 ID의 다른 시도 임시 파일 중 쓰기 핸들이 열려 있지 않은 것을 지운다(늦게 깨어난 그 시도의 rename은 NO_SUCH_FILE — 새 시도가 이어받았다). */
    private fun clearClosedAttempts(username: String, file: Path) {
        val ref = AcquisitionFileRef.fromTempName(file.fileName.toString()) ?: return
        val removed = Files.list(file.parent).use { s ->
            s.toList().filter { p ->
                val name = p.fileName.toString()
                name != file.fileName.toString() && AcquisitionFileRef.fromTempName(name) == ref && !uploads.hasOpenWriter(username, p)
            }
        }.count { runCatching { Files.deleteIfExists(it) }.getOrDefault(false) }
        if (removed > 0) log.info("acquirer SFTP temp cap reached; closed earlier attempts removed fileId={} count={}", ref.value, removed)
    }

    private fun tempsIn(dir: Path?): Int = dir?.let { d ->
        Files.list(d).use { s -> s.filter { AcquisitionFileRef.fromTempName(it.fileName.toString()) != null }.count().toInt() }
    } ?: 0

    private companion object {
        const val INBOX = "inbox"
        const val OUTBOX = "outbox"
        val DIRS = setOf(INBOX, OUTBOX)
        val NAME = Regex("^[A-Za-z0-9._-]{1,128}$")
        val WRITE_OPTIONS = setOf(
            StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.CREATE, StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.DELETE_ON_CLOSE,
        )
        val FORBIDDEN_WRITE_OPTIONS = setOf(StandardOpenOption.APPEND, StandardOpenOption.DELETE_ON_CLOSE)

        /** inbox 한 곳의 업로드 임시 파일 상한(Demo). */
        const val MAX_TEMPS = 32
    }
}
