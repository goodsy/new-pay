package com.paycore.simulator.acquirer.application.required

import java.time.Duration
import java.time.Instant

/**
 * 매입사별 파일 영역 Port(SFTP chroot 아래 inbox·outbox — 구현은 로컬 파일 시스템 adapter). 외부(FINANCIAL)가 SFTP로 올린 파일을 읽고, ACK·결과를 게시한다.
 * DB TX 밖에서 부른다. 받은 최종 파일은 지우거나 옮기지 않는다(FINANCIAL 전송 확인 근거 — ACQUIRING-001 I-24). 지우는 것은 오래된 업로드 임시 파일뿐이다.
 */
interface AcquirerFileArea {

    /** inbox의 최종명 후보(점으로 시작하지 않는 일반 파일 — 업로드 임시명 제외)와 크기·수정 시각. 이름 순. */
    fun inboxFiles(acquirerCode: String): List<InboxEntry>

    /** inbox 파일 하나(없어졌으면 null). [maxBytes]를 넘으면 내용 없이 hash·크기만. */
    fun readInbox(acquirerCode: String, name: String, maxBytes: Int): InboxContent?

    /**
     * outbox에 임시명(`.{name}.tmp`)으로 쓰고 rename으로 게시한다(그 뒤 디렉터리 fsync). 최종명이 이미 있으면 덮어쓰지 않고 바이트를 대조한다
     * (같으면 [PublishOutcome.ALREADY_PRESENT_SAME] — 앞선 게시 뒤 DB 기록이 유실된 재시작, 다르면 [PublishOutcome.ALREADY_PRESENT_DIFFERENT] — 덮어쓰지 않음).
     */
    fun publish(acquirerCode: String, name: String, content: ByteArray): PublishOutcome

    /** 마지막 수정이 [retention]보다 오래된 업로드 임시 파일(시도별 `.part`)을 지우고 지운 수를 돌려준다(끊긴 업로드 잔여물 — 리뷰 m-1 디스크 상한). */
    fun sweepTemps(acquirerCode: String, retention: Duration): Int
}

data class InboxEntry(val name: String, val size: Long, val modifiedAt: Instant)

sealed interface InboxContent {
    val contentSha256: String
    val size: Long

    class Complete(val bytes: ByteArray, override val contentSha256: String) : InboxContent {
        override val size: Long get() = bytes.size.toLong()
    }

    class TooLarge(override val contentSha256: String, override val size: Long) : InboxContent
}

enum class PublishOutcome { PUBLISHED, ALREADY_PRESENT_SAME, ALREADY_PRESENT_DIFFERENT }
