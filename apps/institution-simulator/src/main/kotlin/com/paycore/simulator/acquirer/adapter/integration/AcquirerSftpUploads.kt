package com.paycore.simulator.acquirer.adapter.integration

import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.Handle
import org.apache.sshd.sftp.server.SftpEventListener
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 업로드 쓰기 추적·상한(ACQUIRING-001 2-E rev 0.7 — 리뷰 m-1·m-2). [AcquirerSftpPolicy]와 같은 객체를 서버 하나가 공유한다.
 * - 쓰기로 열린 핸들을 (계정, chroot 안 경로)마다 기록한다 → 열린 쓰기 핸들이 있는 임시 파일은 rename할 수 없다([hasOpenWriter] — 아직 쓰는 중인 바이트가
 *   최종명으로 확정되지 않게). 세션이 끊기면 sshd가 핸들을 닫으므로([Handle.isOpen]이 거짓) 남은 기록이 rename을 영구히 막지 않는다.
 * - 쓰기 요청의 끝(offset + 길이)이 [MAX_FILE_BYTES]를 넘으면 그 요청을 거부한다(디스크 상한 — 받는 쪽 상한·FINANCIAL 봉인 상한과 같다).
 */
class AcquirerSftpUploads : SftpEventListener {

    private val writers = ConcurrentHashMap<String, MutableSet<Handle>>()

    override fun open(session: ServerSession, remoteHandle: String, localHandle: Handle) {
        if (localHandle is FileHandle && StandardOpenOption.WRITE in localHandle.openOptions) {
            writers.computeIfAbsent(key(session.username, localHandle.file)) { ConcurrentHashMap.newKeySet() }.add(localHandle)
        }
    }

    override fun closed(session: ServerSession, remoteHandle: String, localHandle: Handle, thrown: Throwable?) {
        val k = key(session.username, localHandle.file)
        writers.computeIfPresent(k) { _, set -> set.apply { remove(localHandle) }.takeIf { it.isNotEmpty() } }
    }

    override fun writing(
        session: ServerSession, remoteHandle: String, localHandle: FileHandle, offset: Long, data: ByteArray, dataOffset: Int, dataLen: Int,
    ) {
        if (offset < 0 || offset + dataLen > MAX_FILE_BYTES) throw IOException("upload exceeds the acquirer file size limit")
    }

    /** 그 계정이 [path]를 쓰기로 연 핸들이 아직 열려 있는가. */
    fun hasOpenWriter(username: String, path: Path): Boolean = writers[key(username, path)]?.any { it.isOpen } == true

    private fun key(username: String, path: Path) = username + ":" + path.toAbsolutePath().normalize().toString()

    companion object {
        /** 업로드 파일 상한(16 MiB − 1 — FINANCIAL 봉인 바이트 상한 MEDIUMBLOB, Worker 수신 상한과 같다). */
        const val MAX_FILE_BYTES = 16L * 1024 * 1024 - 1
    }
}
