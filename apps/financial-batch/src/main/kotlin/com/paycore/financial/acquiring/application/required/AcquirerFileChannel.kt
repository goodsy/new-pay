package com.paycore.financial.acquiring.application.required

/**
 * 매입사 파일 교환 채널 Port(외부 호출 — 구현은 adapter.integration의 SFTP, D-ACQ-006 클라이언트 키 인증 + host key 고정). **DB TX 밖에서만 부른다.**
 * 경로는 매입사별 inbox(우리가 올림)·outbox(기관이 ACK·결과를 둠) 안의 파일 이름만 받는다(디렉터리 이동 없음 — 이름 문법은 구현이 검사).
 * 실패는 [AcquirerChannelFailure]로만 알린다(메시지에 키·지문·경로 원문 없음).
 */
interface AcquirerFileChannel {
    /** 그 매입사 접속 설정이 있는가(없으면 전송하지 않는다 — fail-closed, 운영 기본값 없음). */
    fun isConfigured(acquirerCode: String): Boolean

    /** 새 접속(host key 검증·공개키 인증까지). 호출자가 닫는다. */
    fun open(acquirerCode: String): AcquirerFileSession
}

interface AcquirerFileSession : AutoCloseable {
    /** inbox의 [name] 크기, 없으면 null. */
    fun inboxSize(name: String): Long?

    fun readInbox(name: String): ByteArray

    /** inbox에 [name]을 만들거나 덮어써 [content]를 쓴다(임시명 전용 — 최종명 덮어쓰기는 기관 서버가 거부한다). */
    fun writeInbox(name: String, content: ByteArray)

    /** inbox 안 이름 변경(대상이 있으면 서버가 거부 — [AcquirerChannelFailure.Refused]). */
    fun renameInbox(from: String, to: String)

    /**
     * outbox 파일 목록(이름·크기 — 2-F). 이름 문법(`[A-Za-z0-9._-]{1,128}`) 밖 이름은 빠진다. 점으로 시작하는 게시 중 임시명(`.{name}.tmp`)도 보일 수 있다 —
     * 받는 쪽이 계약 이름만 고른다(§5.1).
     */
    fun listOutbox(): List<AcquirerRemoteFile>

    /** outbox 파일 전체 바이트(상한 16 MiB − 1 — 넘으면 [AcquirerChannelFailure.Refused]). 읽기 중 끊김은 [AcquirerChannelFailure.Interrupted](부분 바이트를 돌려주지 않는다). */
    fun readOutbox(name: String): ByteArray

    override fun close()
}

/** outbox 목록 항목(크기는 서버 stat 값 — 같은 이름 다른 크기 = 덮어쓰기 의심, 다시 읽어 hash로 판정). */
data class AcquirerRemoteFile(val name: String, val size: Long)

/** 채널 실패. [Refused] = 서버가 명시적으로 거절(작업이 일어나지 않음), [Interrupted] = 접속·입출력 실패(진행 중 작업의 결과 모름). */
sealed class AcquirerChannelFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    class NotConfigured(acquirerCode: String) : AcquirerChannelFailure("no transport is configured for acquirer $acquirerCode", null)

    class HostKeyRejected : AcquirerChannelFailure("server host key is not one of the configured fingerprints", null)

    class AuthenticationFailed(cause: Throwable?) : AcquirerChannelFailure("client key authentication failed", cause)

    class ConnectFailed(cause: Throwable?) : AcquirerChannelFailure("could not connect", cause)

    class Refused(val status: Int) : AcquirerChannelFailure("server refused the operation (status $status)", null)

    class Interrupted(cause: Throwable?) : AcquirerChannelFailure("connection or I/O failed during the operation", cause)
}
