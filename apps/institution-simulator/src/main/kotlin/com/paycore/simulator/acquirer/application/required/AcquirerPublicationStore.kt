package com.paycore.simulator.acquirer.application.required

import java.time.Instant

enum class PublicationKind { ACK, RESULT }

/** 게시 작업 예약(수신 TX가 만든다 — PENDING). */
data class NewPublication(val receiptId: Long, val acquirerCode: String, val kind: PublicationKind, val seq: Int, val fileName: String, val dueAt: Instant)

/** 게시 대기 작업(진행 표 한 행 — [version]은 게시 기록 TX의 기대 version). */
data class PendingPublication(
    val workItemId: Long,
    val receiptId: Long,
    val acquirerCode: String,
    val kind: PublicationKind,
    val seq: Int,
    val fileName: String,
    val dueAt: Instant,
    val version: Long,
)

/**
 * Worker 진행·게시 기록 Port. 진행 표만 상태 열을 바꾼다(PENDING → PUBLISHED, @Version 기대 version — 열 단위 UPDATE 권한), 게시 기록은 append-only.
 * 호출자 TX 안에서만(MANDATORY).
 */
interface AcquirerPublicationStore {
    fun schedule(items: List<NewPublication>, at: Instant)

    /**
     * 게시 시각이 [now] 이하인 PENDING 작업(게시 시각·작업 순). [excluding]은 덮어쓰기 거부로 잠시 미룬 작업 — 영구 PENDING 작업이 묶음을 독점하지 않게 한다
     * (리뷰 n-3).
     */
    fun due(now: Instant, limit: Int, excluding: Set<Long>): List<PendingPublication>

    /**
     * 게시 기록 한 행 + 작업 PUBLISHED. 다른 Worker가 먼저 끝냈으면(version 불일치·이미 PUBLISHED·게시 기록 UNIQUE) false — 아무것도 바꾸지 않는다.
     */
    fun markPublished(publication: PendingPublication, contentSha256: String, contentBytes: Int, at: Instant): Boolean
}
