package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType

/*
 * 잠금 읽기 공통(ARCHITECTURE-CONVENTIONS §10, AUTHN-001 중간 검토 #2 m-1 — MASTER `PessimisticRowLock`과 같은 관례에 한 가지 보강).
 *
 * JPQL `@Lock(PESSIMISTIC_WRITE)` 조회는 `SELECT … FOR UPDATE`로 행을 잠그지만, 같은 TX의 앞선 비잠금 읽기(digest·handle·생성 기록 조회)로 이미 영속성
 * 컨텍스트에 있는 Entity는 **다시 읽지 않는다**. 다른 TX가 그 사이 커밋한 변경(토큰 CONSUMED 등)이 있으면 Hibernate는 version 차이를 OptimisticLockException으로
 * 드러내 진 쪽이 업무 판정("이미 사용됨") 대신 충돌 오류를 받는다(RED: LockReadFreshnessSpec).
 *
 * [lockRow]: 이 TX가 아직 잠그지 않은 행이면 `refresh(row, PESSIMISTIC_WRITE)` = `SELECT … FOR UPDATE`로 잠그며 최신 커밋 값으로 덮어 읽는다. **이미 이 TX가
 * PESSIMISTIC_WRITE로 잠근 행은 다시 읽지 않는다**: 잠근 뒤에는 다른 TX가 바꿀 수 없어 관리 중인 값이 최신이고, Hibernate는 이미 잠긴 행의 refresh를 잠금 절 없는
 * 일반 SELECT로 보내 REPEATABLE READ에서는 TX 시작 시점 스냅샷(더 오래된 값)으로 되돌리거나 flush 전 변경을 지운다(시험에서 확인한 함정 — 운영 TX는 READ COMMITTED지만
 * 격리 수준에 기대지 않는다).
 * [lockRows]: 목록 잠금은 저장소의 native `SELECT id … FOR UPDATE`(최신 커밋 행을 잠그며 조건 판정)로 id를 얻은 뒤 같은 순서로 [lockRow]한다.
 */

internal fun <T : Any> EntityManager.lockRow(type: Class<T>, id: Any): T? {
    val row = find(type, id) ?: return null
    if (getLockMode(row) != LockModeType.PESSIMISTIC_WRITE) refresh(row, LockModeType.PESSIMISTIC_WRITE)
    return row
}

internal fun <T : Any> EntityManager.lockRows(type: Class<T>, ids: List<String>): List<T> =
    ids.map { id -> checkNotNull(lockRow(type, id)) { "a row locked by id must still exist (no deletes)" } }
