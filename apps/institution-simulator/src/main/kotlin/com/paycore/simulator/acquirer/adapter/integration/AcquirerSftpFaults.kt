package com.paycore.simulator.acquirer.adapter.integration

import com.paycore.simulator.acquirer.domain.AcquirerScenarioAction
import com.paycore.simulator.acquirer.domain.AcquirerScenarioRule
import com.paycore.simulator.acquirer.domain.AcquisitionFileRef
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.CopyOption
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SFTP 결정적 장애 주입(ACQUIRING-001 2-E 시나리오 — **Demo 대역**). 매입사 설정의 SFTP 시나리오만 쓴다(난수 없음, 규칙마다 `times`번 — 프로세스 안 계수, 재기동하면
 * 다시 센다):
 * - DISCONNECT_DURING_UPLOAD: 그 파일의 시도별 임시명(`.{id}.dat.{n}.part` — 어느 n이든)의 첫 쓰기 요청에서 세션을 끊는다(데이터는 쓰지 않는다 — 업로드 중 끊김).
 * - DROP_RENAME_RESPONSE: rename을 적용한 **뒤** 응답 전에 세션을 끊는다(rename 응답 유실 — 최종 파일은 있다).
 */
class AcquirerSftpFaults(private val settings: AcquirerSettings) : SftpEventListener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val remaining: Map<AcquirerScenarioRule, AtomicInteger> = IdentityHashMap<AcquirerScenarioRule, AtomicInteger>().apply {
        settings.acquirers.flatMap { it.scenarios.rules }.filter { it.times != null }.forEach { put(it, AtomicInteger(checkNotNull(it.times))) }
    }

    override fun writing(
        session: ServerSession, remoteHandle: String, localHandle: FileHandle, offset: Long, data: ByteArray, dataOffset: Int, dataLen: Int,
    ) {
        val ref = AcquisitionFileRef.fromTempName(localHandle.file.fileName.toString()) ?: return
        if (inject(session, AcquirerScenarioAction.DISCONNECT_DURING_UPLOAD, ref)) {
            session.close(true)
            throw IOException("injected: connection lost during upload")
        }
    }

    override fun moved(session: ServerSession, srcPath: Path, dstPath: Path, opts: MutableCollection<CopyOption>, thrown: Throwable?) {
        if (thrown != null) return
        val ref = AcquisitionFileRef.fromFinalName(dstPath.fileName.toString()) ?: return
        if (inject(session, AcquirerScenarioAction.DROP_RENAME_RESPONSE, ref)) session.close(true)
    }

    private fun inject(session: ServerSession, action: AcquirerScenarioAction, ref: AcquisitionFileRef): Boolean {
        val acquirer = settings.accounts.firstOrNull { it.username == session.username } ?: return false
        val book = settings.acquirers.first { it.code == acquirer.code }.scenarios
        val rule = book.sftpRule(action, ref) ?: return false
        val counter = remaining[rule] ?: return false
        if (counter.getAndUpdate { if (it > 0) it - 1 else 0 } <= 0) return false
        log.warn("acquirer SFTP fault injected by scenario acquirer={} fileId={} action={}", acquirer.code, ref.value, action)
        return true
    }
}
