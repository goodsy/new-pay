package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.AdminIdempotencyKey
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.MasterRejection
import java.time.Instant

/** 관리 명령 멱등 범위(명세 §3.2): (operation, key). 행위자는 범위에 넣지 않는다(같은 키를 다른 행위자가 쓰면 충돌로 드러난다). */
data class AdminRequestScope(val operation: AdminOperation, val key: AdminIdempotencyKey)

data class AdminReservation(
    val scope: AdminRequestScope,
    val requestRecordId: String,
    val requestFingerprint: String,
    val actor: ActorRef,
    val at: Instant,
)

/** 저장된 관리 명령 결과(재생용). 성공은 만든 대상 ID, 업무 거절은 사유와 근거 대상 ID(없을 수 있음). */
sealed interface StoredAdminResult {
    data class Succeeded(val reference: String) : StoredAdminResult

    data class Rejected(val rejection: MasterRejection, val reference: String?) : StoredAdminResult
}

data class StoredAdminRequest(val requestRecordId: String, val requestFingerprint: String, val result: StoredAdminResult?)

sealed interface AdminReservationOutcome {
    /** 이 TX가 새 기록(PENDING)을 만들고 잠갔다. */
    data object Reserved : AdminReservationOutcome

    /** 같은 범위의 기록이 이미 있다(다른 TX가 커밋한 결과). 이 TX가 그 행을 잠그고 있다. */
    data class Existing(val record: StoredAdminRequest) : AdminReservationOutcome
}

/**
 * 관리 명령 멱등 기록(MASTER DB). insert-first(`INSERT … ON DUPLICATE KEY UPDATE` no-op) 후 잠가 읽는다: 같은 키 동시 요청은 이 행에서
 * 직렬화되고 먼저 커밋된 결과가 기준이다. 보존: 삭제 경로 없음(무기한 — 기술 기본값, D-CPC-004 자동 적용 안 함).
 */
interface AdminRequestStore {
    fun reserveOrLock(reservation: AdminReservation): AdminReservationOutcome

    fun complete(scope: AdminRequestScope, result: StoredAdminResult, at: Instant)
}

/** 변경 이력(append-only 감사). [detail]은 변경 후 값(등록번호는 마스킹 값만, D-MD-008). */
data class ChangeRecord(
    val requestRecordId: String,
    val operation: AdminOperation,
    val aggregateType: String,
    val aggregateId: String,
    val changeType: String,
    val actor: ActorRef,
    val occurredAt: Instant,
    val detail: Map<String, Any?>,
)

interface MasterChangeLog {
    fun append(record: ChangeRecord)
}

/**
 * MASTER 통합 이벤트(Outbox, 같은 TX로 append — A02 "MASTER 변경 + 설정 이벤트 Outbox는 한 로컬 TX"). 금융 사실이 아니다
 * (eventCategory INTEGRATION_MASTER_REFERENCE). 소비 계약(Snapshot·순서·watermark)은 MASTER-SNAPSHOT-001이 정한다. payload에 등록번호·
 * Secret·개인정보를 넣지 않는다. [effectKey]는 업무 효과당 하나(UNIQUE).
 */
data class OutboxEvent(
    val eventId: String,
    val eventType: String,
    val schemaVersion: Int,
    val eventCategory: String,
    val producer: String,
    val aggregateType: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val occurredAt: Instant,
    val correlationId: String,
    val causationId: String,
    val effectKey: String,
    val payload: Map<String, Any?>,
)

interface OutboxStore {
    fun append(event: OutboxEvent)
}

/** 서버 생성 식별자(ULID 계열 aggregate ID, 요청 기록 ID, 이벤트 UUID). 시험은 충돌을 주입하려고 대역을 쓴다. */
interface MasterIdGenerator {
    /** `<prefix>_` + ULID 26자. */
    fun newId(prefix: String): String

    /** 요청 기록 ID·이벤트 ID(UUID 문자열 36자). */
    fun newUuid(): String
}
