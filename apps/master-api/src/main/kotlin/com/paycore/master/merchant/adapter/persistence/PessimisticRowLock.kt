package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.application.required.PersistenceFailure
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.transaction.annotation.Transactional

/*
 * application.required 저장 Port 구현(JPA). 공통 관례(payment-api §9와 같다):
 * - `@Transactional(MANDATORY)`: application이 연 로컬 TX 안에서만 동작한다.
 * - 쓰기 끝에 flush해 제약 위반·version 충돌을 Port 안에서 [PersistenceFailure]로 드러낸다(flush ≠ commit: rollback되면 사라진다).
 * - 새 행은 persist만(Spring Data save는 merge가 될 수 있다 — append-only·insert-only 표에서 UPDATE 권한 없음 1142).
 * - 잠금 읽기 [lockRow]: `EntityManager.refresh(row, PESSIMISTIC_WRITE)` = `SELECT … FOR UPDATE`로 다시 읽어 **이미 영속성 컨텍스트에 있던
 *   Entity도 최신 커밋 값**으로 덮는다(같은 TX의 앞선 비잠금 읽기가 오래된 값을 남기지 않게 — 오래된 version으로 갱신하면 낙관적 충돌이 난다).
 */

internal fun <T : Any> EntityManager.lockRow(type: Class<T>, id: Any): T? {
    val row = find(type, id) ?: return null
    refresh(row, LockModeType.PESSIMISTIC_WRITE)
    return row
}
