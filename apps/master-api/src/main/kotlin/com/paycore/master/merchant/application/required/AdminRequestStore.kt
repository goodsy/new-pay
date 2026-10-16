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
