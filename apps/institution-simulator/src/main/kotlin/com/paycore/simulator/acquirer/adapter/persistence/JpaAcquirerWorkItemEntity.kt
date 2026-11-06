package com.paycore.simulator.acquirer.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/**
 * acquirer_work_item 행(Worker 게시 작업 — 가상 매입사 표 중 유일하게 갱신되는 표). JPA 선택(이번 코드):
 * - **변경 감지 + `@Version`**: 게시 TX가 이 TX에서 읽은 Entity의 state·published_at·updated_at을 바꾸면 flush 때 Hibernate가
 *   `UPDATE … SET state, published_at, updated_at, version = version + 1 WHERE work_item_id = ? AND version = ?`를 만든다. 예: 두 Worker가 같은 작업을 동시에
 *   게시해도 둘째의 UPDATE는 0행 → OptimisticLock → 게시 기록 없이 물러난다(파일은 같은 바이트라 무해). 이유: 게시(파일 rename)는 TX 밖이라 행 잠금을 들고
 *   기다리지 않는다. 주의: 다른 TX에서 읽은 값을 덮어쓰지 않도록 Store가 같은 TX에서 다시 읽은 version과 호출자 기대 version을 먼저 비교한다.
 * - 식별·계획 열은 updatable=false(Hibernate UPDATE에 나오지 않는다) — runtime 권한도 바뀌는 네 열만 UPDATE(V12, 나머지 1143).
 */
@Entity
@Table(name = "acquirer_work_item")
class JpaAcquirerWorkItemEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "work_item_id", nullable = false, updatable = false)
    var workItemId: Long? = null
        protected set

    @field:Column(name = "receipt_id", nullable = false, updatable = false)
    var receiptId: Long = 0
        protected set

    @field:Column(name = "acquirer_code", nullable = false, updatable = false, length = 32)
    lateinit var acquirerCode: String
        protected set

    @field:Column(name = "kind", nullable = false, updatable = false, length = 8)
    lateinit var kind: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    @field:Column(name = "file_name", nullable = false, updatable = false, length = 128)
    lateinit var fileName: String
        protected set

    @field:Column(name = "due_at", nullable = false, updatable = false, columnDefinition = "datetime(3)")
    lateinit var dueAt: Instant
        protected set

    @field:Column(name = "state", nullable = false, length = 12)
    lateinit var state: String
        protected set

    @field:Column(name = "published_at", columnDefinition = "datetime(3)")
    var publishedAt: Instant? = null
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(3)")
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "updated_at", nullable = false, columnDefinition = "datetime(3)")
    lateinit var updatedAt: Instant
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    /** PENDING → PUBLISHED(변경 감지 — flush 때 state·published_at·updated_at·version만 UPDATE). */
    fun markPublished(at: Instant) {
        check(state == PENDING) { "only a pending work item is published" }
        state = PUBLISHED
        publishedAt = at
        updatedAt = at
    }

    override fun equals(other: Any?): Boolean = this === other || (other is JpaAcquirerWorkItemEntity && workItemId != null && workItemId == other.workItemId)

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "JpaAcquirerWorkItemEntity(workItemId=$workItemId, fileName=$fileName, state=$state, version=$version)"

    companion object {
        const val PENDING = "PENDING"
        const val PUBLISHED = "PUBLISHED"

        @Suppress("LongParameterList")
        fun newRow(receiptId: Long, acquirerCode: String, kind: String, seq: Int, fileName: String, dueAt: Instant, at: Instant) = JpaAcquirerWorkItemEntity().apply {
            this.receiptId = receiptId
            this.acquirerCode = acquirerCode
            this.kind = kind
            this.seq = seq
            this.fileName = fileName
            this.dueAt = dueAt
            this.state = PENDING
            this.createdAt = at
            this.updatedAt = at
        }
    }
}
