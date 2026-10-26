package com.paycore.admin.account.application.required

import java.time.Instant

/**
 * 복구 회차 기록(§4.9.2·§4.13 — 회차 행이 전역 잠금 순서의 맨 앞). 호출자 TX 안(MANDATORY). runtime 권한: SELECT·INSERT + `closed_at` UPDATE.
 * - [lockOpen]·[lockOpenEpisode]: 열린 회차 행 `FOR UPDATE`(없으면 null — 잠그지 않음). SECURITY_ADMIN·APPROVER를 활성화하는 TX가 먼저 부른다.
 * - [findOpen]: 비잠금 읽기(등록 완료 0단계 선판정·잠금 아래 재확인).
 * - [open]: 새 회차 INSERT + flush — 열린 회차 UNIQUE·번호 PK 1062는 `UniqueConflict(RECOVERY_EPISODE)`(동시 실행 패자).
 * - [close]: 종료 시각 기록(잠근 행에만).
 */
interface RecoveryEpisodeStore {
    fun lockOpen(): Long?

    fun lockOpenEpisode(): RecoveryEpisode?

    fun findOpen(): RecoveryEpisode?

    fun nextEpisodeNo(): Long

    fun open(episode: RecoveryEpisode)

    fun close(episodeNo: Long, now: Instant)

    /** 열린 회차 조건부 종료(다음 복구 실행 TX — 행 잠금 UPDATE `WHERE closed_at IS NULL`, 만료·쓸 수 있는 코드 0 판정은 호출자). 닫았으면 true(0행 = 이미 닫힘). */
    fun closeIfOpen(episodeNo: Long, now: Instant): Boolean
}
